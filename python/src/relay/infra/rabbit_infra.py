"""The RabbitMQ DM-notification job publisher + the shared queue declaration.

Quorum queues with a dead-letter exchange to the DLQ. ``x-delivery-limit`` is a
broker-side backstop only; the worker enforces the 3-attempt cap itself (and on
RabbitMQ 4.x quorum queues redelivery is stamped on ``x-acquired-count``, NOT
``x-delivery-count`` — see the worker).
"""

from __future__ import annotations

import pika
from pika.adapters.blocking_connection import BlockingChannel

from relay import domain
from relay.infra import dead_letter_queue
from relay.infra.codecs import serialize_job


def declare_notification_queues(channel: BlockingChannel, queue: str) -> None:
    """Declare the quorum queue + its DLQ with the SAME arguments the worker and
    harness use (a mismatched redeclare is a channel error)."""
    dlq = dead_letter_queue(queue)
    channel.queue_declare(queue=dlq, durable=True, arguments={"x-queue-type": "quorum"})
    channel.queue_declare(
        queue=queue,
        durable=True,
        arguments={
            "x-queue-type": "quorum",
            "x-delivery-limit": 2,
            "x-dead-letter-exchange": "",
            "x-dead-letter-routing-key": dlq,
        },
    )


class NotificationJobs:
    """Publishes a DM notification job in publisher-confirm mode, awaiting the
    broker's confirmation — the pinned post-commit publish (a failure after
    commit → 500)."""

    def __init__(self, params: pika.ConnectionParameters, queue: str) -> None:
        self._params = params
        self._queue = queue

    def enqueue(self, job: domain.NotificationJob) -> None:
        connection = pika.BlockingConnection(self._params)
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            declare_notification_queues(channel, self._queue)
            channel.basic_publish(
                exchange="",
                routing_key=self._queue,
                body=serialize_job(job),
                properties=pika.BasicProperties(
                    delivery_mode=2, content_type="application/json"
                ),
                mandatory=True,
            )
            # confirm_delivery() makes basic_publish raise UnroutableError /
            # NackError if the broker did not confirm — the awaited ack.
        finally:
            connection.close()
