"""The RabbitMQ notification worker.

Consumes ``notify.dm`` with manual acks, prefetch 1: a job is acked only after
the recorder persists its effect. A failing job is retried up to ``MAX_ATTEMPTS``
then dead-lettered (a final ``requeue=False`` nack routes it to the DLX → DLQ),
so the queue keeps flowing past a poison job.

The attempt cap is enforced HERE via ``x-acquired-count`` (the header a RabbitMQ
4.x quorum queue stamps on a requeued nack), NOT by leaning on the broker's
``x-delivery-limit`` (which counts dead-letter republishes, not requeued nacks,
so it would loop). The correct recorder treats a redelivered duplicate as
success → ack, so a duplicate never crash-loops; the naive variant does not, so
a redelivered duplicate dead-letters after MAX_ATTEMPTS.
"""

from __future__ import annotations

import logging
import threading

import pika

from relay import seams
from relay.infra.codecs import deserialize_job
from relay.infra.rabbit_infra import declare_notification_queues
from relay.workers import MAX_ATTEMPTS

log = logging.getLogger("relay.notification_worker")


class NotificationWorker:
    def __init__(
        self,
        params: pika.ConnectionParameters,
        recorder: seams.NotificationRecorder,
        queue: str,
    ) -> None:
        self._params = params
        self._recorder = recorder
        self._queue = queue
        self._thread: threading.Thread | None = None
        self._connection: pika.BlockingConnection | None = None
        self._stop = threading.Event()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="notify-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        connection = self._connection
        if connection is not None and connection.is_open:
            connection.add_callback_threadsafe(connection.close)
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _run(self) -> None:
        self._connection = pika.BlockingConnection(self._params)
        channel = self._connection.channel()
        declare_notification_queues(channel, self._queue)
        channel.basic_qos(prefetch_count=1)
        for method, properties, body in channel.consume(self._queue, inactivity_timeout=0.2):
            if self._stop.is_set():
                break
            if method is None:
                continue  # inactivity tick — re-check the stop flag
            self._handle(channel, method, properties, body)
        channel.cancel()

    def _handle(self, channel, method, properties, body) -> None:
        try:
            job = deserialize_job(body)
            self._recorder.record(job)
        except Exception:
            attempt = _acquired_count(properties)
            exhausted = attempt >= MAX_ATTEMPTS
            log.debug("notification job failed; attempt=%s exhausted=%s", attempt, exhausted)
            # On the final attempt nack with requeue=False → DLX → DLQ; else requeue.
            channel.basic_nack(method.delivery_tag, requeue=not exhausted)
            return
        channel.basic_ack(method.delivery_tag)


def _acquired_count(properties: pika.BasicProperties) -> int:
    """This delivery attempt (1-based). A quorum queue stamps ``x-acquired-count``
    = prior acquisitions on a requeued nack (absent on first delivery)."""
    headers = getattr(properties, "headers", None) or {}
    raw = headers.get("x-acquired-count")
    if raw is None:
        return 1
    return int(raw) + 1
