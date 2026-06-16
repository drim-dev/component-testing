"""Concrete infrastructure-backed implementations of the app's ports.

Redis (membership cache, unread counters, heartbeats), the HTTP link previewer
with its Redis-backed circuit breaker, the Kafka publisher, the RabbitMQ job
publisher, and the S3 object store. These are the "correct" wiring the
composition root assembles into ``Deps``.
"""

MESSAGE_POSTED_TOPIC = "message-posted"
NOTIFY_QUEUE = "notify.dm"
BUCKET = "relay-attachments"


def dead_letter_queue(queue: str) -> str:
    return queue + ".dlq"
