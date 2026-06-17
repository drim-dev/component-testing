"""The presence gRPC server over Redis (the neighbour's own production code). In a component
test of the Relay API this neighbour is stubbed (see harness), not run — this is the real
implementation it stands in for.
"""

from __future__ import annotations

import grpc
import redis

from relay.presence import KEY_PREFIX, presence_pb2, presence_pb2_grpc


class PresenceService(presence_pb2_grpc.PresenceServicer):
    """Implements the presence gRPC server, backed by Redis."""

    def __init__(self, client: redis.Redis) -> None:
        self._redis = client

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
        for user_id in request.user_ids:
            yield presence_pb2.PresenceStatus(user_id=user_id, online=self._online(user_id))

    def _online(self, user_id: str) -> bool:
        return self._redis.exists(KEY_PREFIX + user_id) > 0
