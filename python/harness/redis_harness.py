"""The Redis harness (RedisHarness): membership cache, unread counters, breaker
state, presence keys. Seed = set keys directly (pre-warm a cache to prove
invalidation). Assert = read keys/TTLs. Reset = FLUSHDB (the trivially fast
reset — contrast with Postgres TRUNCATE).
"""

from __future__ import annotations

import redis
from testcontainers.redis import RedisContainer

from harness.images import REDIS_IMAGE


class RedisHarness:
    def __init__(self) -> None:
        self._container: RedisContainer | None = None
        self._client: redis.Redis | None = None
        self._host = ""
        self._port = 0

    def start(self) -> None:
        self._container = RedisContainer(image=REDIS_IMAGE)
        self._container.start()
        self._host = self._container.get_container_host_ip()
        self._port = int(self._container.get_exposed_port(6379))
        self._client = redis.Redis(host=self._host, port=self._port, decode_responses=True)

    @property
    def client(self) -> redis.Redis:
        assert self._client is not None
        return self._client

    @property
    def host(self) -> str:
        return self._host

    @property
    def port(self) -> int:
        return self._port

    def members_cache_present(self, channel_id: str) -> bool:
        return self.client.exists("members:" + channel_id) > 0

    def cached_members(self, channel_id: str) -> set[str]:
        return set(self.client.smembers("members:" + channel_id))

    def reset(self) -> None:
        self.client.flushdb()

    def stop(self) -> None:
        if self._client is not None:
            self._client.close()
        if self._container is not None:
            self._container.stop()
