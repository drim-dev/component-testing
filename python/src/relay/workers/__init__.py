"""Background broker consumers: the Kafka feed-fanout consumer and the RabbitMQ
notification worker. Both take a seam (FeedProjector / NotificationRecorder) so
a consumer-side naive variant is injected through the same constructor seam.
Offsets/acks are applied only AFTER the seam's effect is persisted, so the
harness's await-idle assertion implies the effects are durable.
"""

MAX_ATTEMPTS = 3
