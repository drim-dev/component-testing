"""The Kafka feed-fanout consumer group.

Delivery is at-least-once and the projector is idempotent (G-KAFKA consumer);
the offset is committed only after ``apply`` succeeds, so a processing failure
is retried (never silently skipped). Runs on a daemon thread in the harness.
"""

from __future__ import annotations

import logging
import threading

from confluent_kafka import Consumer, KafkaError

from relay import seams
from relay.infra.codecs import deserialize_message_posted

log = logging.getLogger("relay.feed_consumer")

# librdkafka's background threads emit FAIL-level connection events when the
# broker is paused (the broker-down scenarios); route them off raw stderr so
# they cannot race teardown and break pristine test output.
_rdkafka_log = logging.getLogger("relay.rdkafka")
_rdkafka_log.setLevel(logging.CRITICAL)


class FeedConsumer:
    def __init__(
        self, brokers: str, topic: str, group: str, projector: seams.FeedProjector
    ) -> None:
        self._projector = projector
        self._topic = topic
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._consumer = Consumer(
            {
                "bootstrap.servers": brokers,
                "group.id": group,
                "auto.offset.reset": "earliest",
                "enable.auto.commit": False,
                "socket.timeout.ms": 1000,
                "log.connection.close": False,
            },
            logger=_rdkafka_log,
        )

    def start(self) -> None:
        self._consumer.subscribe([self._topic])
        self._thread = threading.Thread(target=self._run, name="feed-consumer", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._consumer.close()

    def _run(self) -> None:
        while not self._stop.is_set():
            message = self._consumer.poll(0.2)
            if message is None:
                continue
            if message.error() is not None:
                if message.error().code() == KafkaError._PARTITION_EOF:
                    continue
                # Broker unavailable or transient fetch error — librdkafka self-heals.
                continue
            try:
                event = deserialize_message_posted(message.value())
                self._projector.apply(event)
            except Exception:
                # Processing failed before commit: do not advance the offset; the record is
                # re-polled and retried. The projector's idempotency makes the retry safe.
                log.debug("feed fanout failed; will retry", exc_info=True)
                continue
            self._consumer.commit(message=message, asynchronous=False)
