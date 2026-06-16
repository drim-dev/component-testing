"""The correct G-KAFKA producer seam: it AWAITS broker confirmation.

If the broker is unavailable the synchronous produce fails to confirm within the
bounded window, the caller rolls back the message transaction, and the API
answers 503 — never fire-and-forget. The confirm bound (~3 s) keeps a paused
broker surfacing the pinned 503 promptly and deterministically (the zero-flake
gate), never hanging on producer retries.
"""

from __future__ import annotations

import logging
import threading

from confluent_kafka import KafkaError, Message, Producer

from relay import apierr, domain
from relay.infra.codecs import serialize_message_posted

PUBLISH_CONFIRM_TIMEOUT = 3.0  # seconds

# The broker-down scenarios deliberately pause the broker, so librdkafka's
# background threads emit FAIL-level connection-refused events. Routing them
# through a Python logger keeps them off raw stderr (which races teardown and
# breaks pristine test output); the 503 surfaces through the bounded confirm,
# not the log.
_rdkafka_log = logging.getLogger("relay.rdkafka")
_rdkafka_log.setLevel(logging.CRITICAL)


class KafkaPublisher:
    def __init__(self, brokers: str, topic: str) -> None:
        self._topic = topic
        # message.timeout.ms bounds librdkafka's own delivery attempts so a down
        # broker's callback fires with an error rather than retrying forever.
        self._producer = Producer(
            {
                "bootstrap.servers": brokers,
                "message.timeout.ms": int(PUBLISH_CONFIRM_TIMEOUT * 1000),
                "socket.timeout.ms": 1000,
                "reconnect.backoff.max.ms": 500,
                "log.connection.close": False,
            },
            logger=_rdkafka_log,
        )

    def publish(self, event: domain.MessagePosted) -> None:
        confirmed = threading.Event()
        result: dict[str, object] = {}

        def on_delivery(err: KafkaError | None, _msg: Message) -> None:
            result["err"] = err
            confirmed.set()

        self._producer.produce(
            self._topic,
            key=event.channel_id.encode(),
            value=serialize_message_posted(event),
            on_delivery=on_delivery,
        )
        # poll drives delivery callbacks; bound the wait so a paused broker 503s fast.
        deadline = PUBLISH_CONFIRM_TIMEOUT
        step = 0.05
        waited = 0.0
        while not confirmed.is_set() and waited < deadline:
            self._producer.poll(step)
            waited += step
        if not confirmed.is_set() or result.get("err") is not None:
            raise apierr.unavailable("events:unavailable", "The event broker is unavailable.")
