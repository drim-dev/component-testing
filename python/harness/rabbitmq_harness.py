"""The RabbitMQ harness (queues / acks / DLQ — different semantics from Kafka's
log/offsets).

Seed = publish a job directly (incl. duplicate and poison). Assert = await-until
on queue stats (ready via AMQP passive declare; unacked via the management API,
which refreshes on an interval — so "settled" must hold across two spaced
samples). Reset = purge + drain.
"""

from __future__ import annotations

import time

import httpx
import pika
from testcontainers.rabbitmq import RabbitMqContainer

from harness.images import RABBITMQ_IMAGE
from relay import domain
from relay.infra import dead_letter_queue
from relay.infra.codecs import serialize_job
from relay.infra.rabbit_infra import declare_notification_queues

QUEUE = "notify.dm"
NAIVE_QUEUE = "notify.dm.naive"


class RabbitMqHarness:
    def __init__(self) -> None:
        self._container: RabbitMqContainer | None = None
        self._params: pika.ConnectionParameters | None = None
        self._mgmt_url = ""
        self._mgmt_auth = ("guest", "guest")
        self._host = ""
        self._port = 0

    def start(self) -> None:
        self._container = RabbitMqContainer(image=RABBITMQ_IMAGE)
        # The module only maps 5672; the management API (queue-depth / unacked
        # stats the await-settled assertions need) lives on 15672 — expose it.
        self._container.with_exposed_ports(5672, 15672)
        # A short statistics interval so unacked counts refresh fast enough to settle.
        self._container.with_env(
            "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 500"
        )
        self._container.start()
        self._host = self._container.get_container_host_ip()
        self._port = int(self._container.get_exposed_port(5672))
        self._params = pika.ConnectionParameters(
            host=self._host,
            port=self._port,
            credentials=pika.PlainCredentials("guest", "guest"),
            heartbeat=30,
            blocked_connection_timeout=10,
        )
        mgmt_port = int(self._container.get_exposed_port(15672))
        self._mgmt_url = f"http://{self._host}:{mgmt_port}"

        self._await_management_ready()
        connection = self._connect_with_retry()
        channel = connection.channel()
        declare_notification_queues(channel, QUEUE)
        declare_notification_queues(channel, NAIVE_QUEUE)
        connection.close()

    def _connect_with_retry(self, deadline: float = 30.0) -> pika.BlockingConnection:
        stop = time.monotonic() + deadline
        last_error: Exception | None = None
        while time.monotonic() < stop:
            try:
                return pika.BlockingConnection(self._params)
            except (pika.exceptions.AMQPError, OSError) as err:
                last_error = err
                time.sleep(0.5)
        raise RuntimeError(f"could not connect to RabbitMQ AMQP: {last_error}")

    @property
    def params(self) -> pika.ConnectionParameters:
        assert self._params is not None
        return self._params

    def publish(self, job: domain.NotificationJob, queue: str = QUEUE) -> None:
        """Seed a job directly (a duplicate of a delivered one, or poison)."""
        connection = pika.BlockingConnection(self._params)
        try:
            channel = connection.channel()
            channel.basic_publish(
                exchange="",
                routing_key=queue,
                body=serialize_job(job),
                properties=pika.BasicProperties(delivery_mode=2, content_type="application/json"),
            )
        finally:
            connection.close()

    def ready_count(self, queue: str) -> int:
        """Real-time ready count via AMQP passive declare (management stats lag)."""
        connection = pika.BlockingConnection(self._params)
        try:
            channel = connection.channel()
            result = channel.queue_declare(
                queue=queue, durable=True, passive=True, arguments={"x-queue-type": "quorum"}
            )
            return result.method.message_count
        finally:
            connection.close()

    def await_settled(self, queue: str, deadline: float = 15.0) -> None:
        """Block until a queue is fully settled: nothing ready AND nothing in
        flight. Unacked is only visible via the management API (interval-
        refreshed), so the condition must hold across TWO samples spaced wider
        than the 500 ms stats interval."""
        stop = time.monotonic() + deadline
        settled_samples = 0
        while settled_samples < 2:
            if time.monotonic() > stop:
                raise TimeoutError(f"queue {queue} did not settle")
            ready = self.ready_count(queue)
            _, unacked = self._queue_stats(queue)
            if ready == 0 and unacked == 0:
                settled_samples += 1
                if settled_samples < 2:
                    time.sleep(0.6)
            else:
                settled_samples = 0
                time.sleep(0.1)

    def await_depth(self, queue: str, depth: int, deadline: float = 15.0) -> None:
        """Block until a (consumer-less, e.g. DLQ) queue holds exactly depth messages."""
        stop = time.monotonic() + deadline
        while time.monotonic() < stop:
            if self.ready_count(queue) == depth:
                return
            time.sleep(0.1)
        raise TimeoutError(f"queue {queue} did not reach depth {depth}")

    def drain(self) -> None:
        """Purge everything ready then wait out anything in flight, across all queues."""
        queues = [
            QUEUE,
            dead_letter_queue(QUEUE),
            NAIVE_QUEUE,
            dead_letter_queue(NAIVE_QUEUE),
        ]
        stop = time.monotonic() + 15.0
        while time.monotonic() < stop:
            settled = True
            connection = pika.BlockingConnection(self._params)
            try:
                channel = connection.channel()
                for queue in queues:
                    ready = self.ready_count(queue)
                    if ready > 0:
                        channel.queue_purge(queue)
                    _, unacked = self._queue_stats(queue)
                    settled = settled and ready == 0 and unacked == 0
            finally:
                connection.close()
            if settled:
                return
            time.sleep(0.1)

    def reset(self) -> None:
        self.drain()

    def _queue_stats(self, queue: str) -> tuple[int, int]:
        endpoint = f"{self._mgmt_url}/api/queues/%2F/{queue}"
        try:
            response = httpx.get(endpoint, auth=self._mgmt_auth, timeout=5)
        except httpx.HTTPError:
            return (0, 0)
        if response.status_code != 200:
            return (0, 0)
        data = response.json()
        return (data.get("messages_ready", 0), data.get("messages_unacknowledged", 0))

    def _await_management_ready(self, deadline: float = 60.0) -> None:
        stop = time.monotonic() + deadline
        while time.monotonic() < stop:
            try:
                response = httpx.get(
                    self._mgmt_url + "/api/overview", auth=self._mgmt_auth, timeout=2
                )
                if response.status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.25)
        raise RuntimeError("RabbitMQ management API did not become ready")

    def stop(self) -> None:
        if self._container is not None:
            self._container.stop()
