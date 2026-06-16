"""The Fixture composes all dependency harnesses for the suite (one Docker host,
one suite) and builds the assembled Relay app against the real containers.

It is the TestFixture-style composition the spec calls for; extensibility = add
a harness field, not runtime re-composition (04-dependencies.md §9). The app is
driven through FastAPI's TestClient — a real ASGI transport over the whole
middleware + handler stack (``raise_server_exceptions=False`` so the pinned 500
handler runs instead of the exception escaping the client).
"""

from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager

import grpc
from fastapi import FastAPI
from fastapi.testclient import TestClient

from harness.database import DatabaseHarness
from harness.kafka_harness import (
    GROUP,
    NAIVE_GROUP,
    NAIVE_TOPIC,
    TOPIC,
    KafkaHarness,
)
from harness.llm_harness import LlmHarness
from harness.presence_harness import PresenceHarness
from harness.rabbitmq_harness import NAIVE_QUEUE, QUEUE, RabbitMqHarness
from harness.redis_harness import RedisHarness
from harness.s3_harness import S3Harness
from harness.unfurl_harness import UnfurlHarness
from relay.app.build import build_correct_deps, create_app
from relay.app.deps import Deps
from relay.idgen import Factory
from relay.store import Store
from relay.workers.feed_consumer import FeedConsumer
from relay.workers.notification_worker import NotificationWorker


class Fixture:
    def __init__(self) -> None:
        self.database = DatabaseHarness()
        self.redis = RedisHarness()
        self.kafka = KafkaHarness()
        self.rabbit = RabbitMqHarness()
        self.s3 = S3Harness()
        self.llm = LlmHarness()
        self.unfurl = UnfurlHarness()
        self.presence: PresenceHarness | None = None

        self.store: Store | None = None
        self._presence_channel: grpc.Channel | None = None
        self._feed_consumer: FeedConsumer | None = None
        self._notify_worker: NotificationWorker | None = None
        self.app: FastAPI | None = None
        self.client: TestClient | None = None
        self.deps: Deps | None = None

    def start(self) -> None:
        for harness in (self.database, self.redis, self.kafka, self.rabbit, self.s3):
            harness.start()
        self.llm.start()
        self.unfurl.start()
        # Presence depends on Redis (shares the connection), so it starts after.
        self.presence = PresenceHarness(self.redis.host, self.redis.port)
        self.presence.start()

        self.store = Store(self.database.dsn)
        self._presence_channel = grpc.insecure_channel(self.presence.addr)

        self.deps = build_correct_deps(
            store=self.store,
            redis_client=self.redis.client,
            kafka_brokers=self.kafka.brokers,
            rabbit_params=self.rabbit.params,
            s3_client=self.s3.client,
            presence_channel=self._presence_channel,
            summary_model=self.llm.model,
            unfurl_base_url=self.unfurl.base_url,
            generator_id=1,
        )
        self.app = create_app(self.deps)
        self.client = TestClient(self.app, raise_server_exceptions=False)
        self._start_workers(self.deps)

    def _start_workers(self, deps: Deps) -> None:
        self._feed_consumer = FeedConsumer(self.kafka.brokers, TOPIC, GROUP, deps.feed)
        self._feed_consumer.start()
        self._notify_worker = NotificationWorker(
            self.rabbit.params, deps.notification_recorder, QUEUE
        )
        self._notify_worker.start()

    def reset(self) -> None:
        """Return every dependency to a clean state. Brokers are drained BEFORE
        the DB truncate so a late event/job never writes into the next test's
        clean state."""
        assert self.store is not None
        self.kafka.await_consumed(TOPIC, GROUP)
        self.rabbit.drain()
        self.database.reset(self.store.engine)
        self.redis.reset()
        self.s3.reset()
        self.llm.reset()
        self.unfurl.reset()
        if self.presence is not None:
            self.presence.reset()

    @contextmanager
    def override_seam(self, provider: Callable, naive_impl: object) -> Iterator[TestClient]:
        """The §0.4 injection seam, FastAPI idiom: replace exactly ONE seam
        provider with its naive variant via ``app.dependency_overrides`` (the
        clean analog of .NET RemoveAll+re-register / Go's struct-field swap /
        Spring's @Primary bean), scoped to one test, then cleared. The same
        live containers; only the seam differs."""
        assert self.app is not None and self.client is not None
        self.app.dependency_overrides[provider] = lambda: naive_impl
        try:
            yield self.client
        finally:
            self.app.dependency_overrides.pop(provider, None)

    def naive_feed_id_factory(self) -> Factory:
        """A DISTINCT generator id (2) so naive-host ids never collide with the
        suite's seeded data (the §0.4 distinct-generator rule)."""
        return Factory(2)

    @contextmanager
    def naive_feed_consumer(self, projector: object) -> Iterator[None]:
        """Run a naive Kafka consumer against the PARALLEL topic/group so its
        deliberately-buggy fan-out never races the suite's correct consumer
        (the Java-handoff isolation rule). The test publishes to NAIVE_TOPIC."""
        consumer = FeedConsumer(self.kafka.brokers, NAIVE_TOPIC, NAIVE_GROUP, projector)
        consumer.start()
        try:
            yield
        finally:
            consumer.stop()

    @contextmanager
    def naive_notification_worker(self, recorder: object) -> Iterator[None]:
        """Run a naive Rabbit worker against the PARALLEL queue so its
        non-idempotent processing never races the suite's correct worker."""
        worker = NotificationWorker(self.rabbit.params, recorder, NAIVE_QUEUE)
        worker.start()
        try:
            yield
        finally:
            worker.stop()

    def stop(self) -> None:
        if self._feed_consumer is not None:
            self._feed_consumer.stop()
        if self._notify_worker is not None:
            self._notify_worker.stop()
        if self.client is not None:
            self.client.close()
        if self._presence_channel is not None:
            self._presence_channel.close()
        if self.store is not None:
            self.store.close()
        if self.presence is not None:
            self.presence.stop()
        for harness in (
            self.unfurl,
            self.llm,
            self.s3,
            self.rabbit,
            self.kafka,
            self.redis,
            self.database,
        ):
            harness.stop()
