"""Relay's test harness: the per-dependency harnesses (DatabaseHarness,
RedisHarness, KafkaHarness, RabbitMqHarness, S3Harness, LlmHarness,
UnfurlHarness, PresenceHarness) and the Fixture that composes them.

Each harness answers the same five questions — Start, wire the seam, Seed,
Assert, Reset — in its own shape (04-dependencies.md §0.1). Real deps run via
testcontainers-python (digest-pinned, the SAME digests the .NET pilot recorded);
the LLM is a hand-rolled in-process fake; outbound HTTP is a real local stub
server. Harness code is reusable bricks only — no test-case logic.
"""
