"""Boots the REAL companion-owned presence gRPC service on an ephemeral
127.0.0.1 port over a real socket, so the API consumes it through genuine gRPC
(cleartext h2c) — the transport-agnostic proof (not an in-process double).

It shares the suite's Redis so a heartbeat is observable through the stream.
Seed = set presence keys directly. Fault control = arm the stream to fail after
N (the deterministic partial-stream probe). Reset = clear the fault flag
(presence keys are cleared by the suite's Redis FLUSHDB).
"""

from __future__ import annotations

from concurrent import futures

import grpc
import redis

from relay.presence import KEY_PREFIX, presence_pb2_grpc
from relay.presence.service import PresenceService, StreamFault

PRESENCE_TTL = 60


class PresenceHarness:
    def __init__(self, redis_host: str, redis_port: int) -> None:
        self._redis = redis.Redis(host=redis_host, port=redis_port, decode_responses=True)
        self._fault = StreamFault()
        self._server: grpc.Server | None = None
        self._addr = ""

    def start(self) -> None:
        self._server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
        presence_pb2_grpc.add_PresenceServicer_to_server(
            PresenceService(self._redis, self._fault), self._server
        )
        port = self._server.add_insecure_port("127.0.0.1:0")
        self._addr = f"127.0.0.1:{port}"
        self._server.start()

    @property
    def addr(self) -> str:
        return self._addr

    def set_online(self, user_id: str) -> None:
        """Mark a user online directly (the same key the heartbeat writes), 60 s TTL."""
        self._redis.set(KEY_PREFIX + user_id, "1", ex=PRESENCE_TTL)

    def fail_stream_after(self, n: int) -> None:
        """Arm the partial-stream fault: the next stream emits n statuses then aborts."""
        self._fault.fail_after(n)

    def reset(self) -> None:
        self._fault.clear()

    def stop(self) -> None:
        if self._server is not None:
            self._server.stop(grace=2).wait()
        self._redis.close()
