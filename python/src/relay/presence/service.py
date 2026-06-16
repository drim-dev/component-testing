"""The presence gRPC server over Redis, plus its test-only stream fault flag."""

from __future__ import annotations

import threading

import grpc
import redis

from relay.presence import KEY_PREFIX, presence_pb2, presence_pb2_grpc


class StreamFault:
    """Test-only fault control (04-dependencies.md §8): armed to "fail after N",
    the streaming RPC writes N statuses then aborts mid-stream with a gRPC error
    — the deterministic partial-stream probe. Unset (the production default) →
    the stream always completes cleanly; the code path is identical either way.
    """

    def __init__(self) -> None:
        self._fail_after: int | None = None
        self._lock = threading.Lock()

    def fail_after(self, messages: int) -> None:
        with self._lock:
            self._fail_after = messages

    def clear(self) -> None:
        with self._lock:
            self._fail_after = None

    def limit(self) -> int | None:
        with self._lock:
            return self._fail_after


class PresenceService(presence_pb2_grpc.PresenceServicer):
    """Implements the presence gRPC server. Backed by Redis; honors a fault flag."""

    def __init__(self, client: redis.Redis, fault: StreamFault) -> None:
        self._redis = client
        self._fault = fault

    def GetPresence(  # noqa: N802 (gRPC stub name)
        self, request: presence_pb2.GetPresenceRequest, context: grpc.ServicerContext
    ) -> presence_pb2.PresenceStatus:
        return presence_pb2.PresenceStatus(
            user_id=request.user_id, online=self._online(request.user_id)
        )

    def StreamChannelPresence(  # noqa: N802 (gRPC stub name)
        self,
        request: presence_pb2.StreamChannelPresenceRequest,
        context: grpc.ServicerContext,
    ):
        limit = self._fault.limit()
        for index, user_id in enumerate(request.user_ids):
            if limit is not None and index >= limit:
                context.abort(
                    grpc.StatusCode.UNAVAILABLE,
                    "presence stream fault (test-only): aborting mid-stream",
                )
            yield presence_pb2.PresenceStatus(user_id=user_id, online=self._online(user_id))

    def _online(self, user_id: str) -> bool:
        return self._redis.exists(KEY_PREFIX + user_id) > 0
