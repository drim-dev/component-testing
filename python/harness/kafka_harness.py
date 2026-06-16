"""The Kafka harness (KafkaHarness): a single-node apache/kafka KRaft broker.

We do NOT use a Testcontainers Kafka module: that module picks its broker
starter script by *detecting the image*, and a digest-pinned reference
(apache/kafka:3.9.0@sha256:…) defeats apache-vs-confluent detection, so it falls
back to the confluent script referencing /etc/confluent/docker/* — paths absent
in apache/kafka, leaving the container dead. Standing the broker up by hand keeps
the LOCKED digest pin and the apache/kafka KRaft layout (/etc/kafka/*). This is
the working go/harness/kafka.go shape, ported to docker-py.

The advertised-listener problem (Kafka must advertise the host-mapped port,
unknown until after start) is solved as the module solves it: the entrypoint
blocks on a starter script we write once the mapped port is known, which rewrites
KAFKA_ADVERTISED_LISTENERS and execs the image's own entrypoint.

Seed = produce a crafted event directly. Assert = await-until on committed
offset >= end offset for the group (never sleep). Fault control = pause/unpause
the broker container (a stopped KRaft container trips over its formatted storage
on restart; pausing freezes it and unpausing recovers deterministically).
"""

from __future__ import annotations

import contextlib
import io
import logging
import tarfile
import time

from confluent_kafka import Producer
from confluent_kafka.admin import AdminClient, NewTopic
from testcontainers.core.container import DockerContainer
from testcontainers.core.waiting_utils import wait_for_logs

from harness.images import KAFKA_IMAGE

# Topic / group are the suite's real fanout pair. The naive pair is parallel so a
# naive consumer host never races the suite's correct consumer (§0.4).
TOPIC = "message-posted"
GROUP = "feed-fanout"
NAIVE_TOPIC = "message-posted-naive"
NAIVE_GROUP = "feed-fanout-naive"

_BROKER_PORT = 9093
_STARTER_SCRIPT = "/tmp/relay_kafka_start.sh"

# stop_broker pauses the container, so every librdkafka client's background
# threads log FAIL-level connection-refused events. Route them off raw stderr
# (which races teardown and breaks pristine output) through a quiet logger.
_rdkafka_log = logging.getLogger("relay.rdkafka")
_rdkafka_log.setLevel(logging.CRITICAL)
_QUIET = {"log.connection.close": False}


class KafkaHarness:
    def __init__(self) -> None:
        self._container: DockerContainer | None = None
        self._brokers = ""
        self._producer: Producer | None = None
        self._admin: AdminClient | None = None

    @property
    def brokers(self) -> str:
        return self._brokers

    def start(self) -> None:
        container = (
            DockerContainer(KAFKA_IMAGE)
            .with_exposed_ports(_BROKER_PORT)
            .with_env("CLUSTER_ID", "relay-kraft-cluster-0")
            .with_env("KAFKA_NODE_ID", "1")
            .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
            .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9094")
            # Empty-host listener form binds all interfaces; the literal 0.0.0.0
            # trips KafkaConfig.validateValues during the storage-format step.
            .with_env(
                "KAFKA_LISTENERS",
                f"PLAINTEXT://:9092,CONTROLLER://:9094,HOST://:{_BROKER_PORT}",
            )
            .with_env(
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
            )
            .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
            .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .with_env(
                "KAFKA_ADVERTISED_LISTENERS",
                f"PLAINTEXT://localhost:9092,HOST://localhost:{_BROKER_PORT}",
            )
        )
        # The entrypoint blocks until the starter script lands, then execs it.
        container.with_kwargs(
            entrypoint="sh -c",
        )
        container.with_command(
            f"'while [ ! -f {_STARTER_SCRIPT} ]; do sleep 0.1; done; exec {_STARTER_SCRIPT}'"
        )
        container.start()
        self._container = container

        host = container.get_container_host_ip()
        mapped = int(container.get_exposed_port(_BROKER_PORT))
        self._write_starter_script(host, mapped)

        wait_for_logs(container, "Kafka Server started", timeout=120)
        self._brokers = f"{host}:{mapped}"
        self._admin = AdminClient(
            {"bootstrap.servers": self._brokers, **_QUIET}, logger=_rdkafka_log
        )
        self._producer = Producer(
            {"bootstrap.servers": self._brokers, **_QUIET}, logger=_rdkafka_log
        )
        self._create_topics(TOPIC, NAIVE_TOPIC)

    def _write_starter_script(self, host: str, mapped: int) -> None:
        advertised = f"PLAINTEXT://localhost:9092,HOST://{host}:{mapped}"
        script = (
            "#!/bin/sh\n"
            f"export KAFKA_ADVERTISED_LISTENERS='{advertised}'\n"
            "exec /etc/kafka/docker/run\n"
        )
        self._put_file(_STARTER_SCRIPT, script.encode(), mode=0o755)

    def _put_file(self, path: str, content: bytes, *, mode: int) -> None:
        wrapped = self._container.get_wrapped_container()
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as tar:
            info = tarfile.TarInfo(name=path.lstrip("/"))
            info.size = len(content)
            info.mode = mode
            tar.addfile(info, io.BytesIO(content))
        buffer.seek(0)
        wrapped.put_archive("/", buffer.getvalue())

    def _create_topics(self, *topics: str) -> None:
        futures = self._admin.create_topics(
            [NewTopic(t, num_partitions=1, replication_factor=1) for t in topics]
        )
        for future in futures.values():
            # TopicAlreadyExists on a re-run is fine; only a request-level error matters.
            with contextlib.suppress(Exception):
                future.result(timeout=15)

    def publish(self, topic: str, key: str, value: bytes) -> None:
        """Seed a crafted event directly to a topic."""
        self._producer.produce(topic, key=key.encode(), value=value)
        self._producer.flush(10)

    def await_consumed(self, topic: str, group: str, deadline: float = 10.0) -> None:
        """Block until the consumer group has committed everything published to
        the topic (committed offset >= end offset on partition 0). Because the
        app commits only after the projector persists, this implies every
        consumed event's effects are durable."""
        from confluent_kafka import Consumer, KafkaException, TopicPartition

        end = self._end_offset(topic)
        if end <= 0:
            return
        consumer = Consumer(
            {
                "bootstrap.servers": self._brokers,
                "group.id": group,
                "enable.auto.commit": False,
                **_QUIET,
            },
            logger=_rdkafka_log,
        )
        try:
            stop = time.monotonic() + deadline
            partition = TopicPartition(topic, 0)
            while time.monotonic() < stop:
                try:
                    committed = consumer.committed([partition], timeout=5)
                    offset = committed[0].offset if committed else 0
                except KafkaException:
                    # NOT_COORDINATOR / transient broker-rejoin race after a
                    # pause/unpause — keep polling within the deadline.
                    offset = -1
                if offset >= end:
                    return
                time.sleep(0.1)
        finally:
            consumer.close()

    def _end_offset(self, topic: str) -> int:
        from confluent_kafka import Consumer, KafkaException, TopicPartition

        consumer = Consumer(
            {"bootstrap.servers": self._brokers, "group.id": "relay-harness-probe", **_QUIET},
            logger=_rdkafka_log,
        )
        try:
            for _ in range(20):
                try:
                    _low, high = consumer.get_watermark_offsets(
                        TopicPartition(topic, 0), timeout=5, cached=False
                    )
                    return high
                except KafkaException:
                    time.sleep(0.25)
            return -1
        finally:
            consumer.close()

    def stop_broker(self) -> None:
        """Pause the broker container — produce requests then time out exactly as
        if it were gone. Sanctioned by 04-dependencies.md §3."""
        self._container.get_wrapped_container().pause()

    def start_broker(self) -> None:
        """Unpause the broker and block until it is ready AND the group
        coordinator answers, so the next test never races the rejoin/rebalance
        (the zero-flake gate's key invariant — a freshly-unpaused KRaft node
        briefly reports NOT_COORDINATOR for committed-offset queries)."""
        container = self._container.get_wrapped_container()
        container.reload()
        if container.status == "paused":
            container.unpause()
        self._await_ready()
        self._await_coordinator(GROUP)

    def _await_ready(self, deadline: float = 30.0) -> None:
        stop = time.monotonic() + deadline
        while time.monotonic() < stop:
            try:
                metadata = self._admin.list_topics(timeout=2)
                if TOPIC in metadata.topics:
                    return
            except Exception:
                pass
            time.sleep(0.25)

    def _await_coordinator(self, group: str, deadline: float = 30.0) -> None:
        from confluent_kafka import Consumer, KafkaException, TopicPartition

        consumer = Consumer(
            {
                "bootstrap.servers": self._brokers,
                "group.id": group,
                "enable.auto.commit": False,
                **_QUIET,
            },
            logger=_rdkafka_log,
        )
        try:
            stop = time.monotonic() + deadline
            while time.monotonic() < stop:
                try:
                    consumer.committed([TopicPartition(TOPIC, 0)], timeout=5)
                    return
                except KafkaException:
                    time.sleep(0.25)
        finally:
            consumer.close()

    def reset(self) -> None:
        # Offsets are monotonic; idempotency keys differ per test run. Draining is
        # the fixture's job (await_consumed) before truncation.
        return

    def stop(self) -> None:
        if self._container is not None:
            self._container.stop()
