"""The API-side presence client — the correct G-GRPC seam."""

from __future__ import annotations

import grpc

from relay import domain
from relay.presence import presence_pb2, presence_pb2_grpc
from relay.seams import PresenceResult


class PresenceClient:
    """The correct G-GRPC seam: consume the server stream to its CLEAN
    end-of-stream and report a mid-stream transport error as ``incomplete`` — so
    the handler returns 502 and NEVER a partial member list as complete. The
    naive variant swallows the error and returns whatever arrived.
    """

    def __init__(self, channel: grpc.Channel) -> None:
        self._stub = presence_pb2_grpc.PresenceStub(channel)

    def user_presence(self, user_id: str) -> bool:
        status = self._stub.GetPresence(presence_pb2.GetPresenceRequest(user_id=user_id))
        return status.online

    def channel_presence(self, user_ids: list[str]) -> PresenceResult:
        statuses: list[domain.PresenceStatus] = []
        stream = self._stub.StreamChannelPresence(
            presence_pb2.StreamChannelPresenceRequest(user_ids=user_ids)
        )
        try:
            for msg in stream:
                statuses.append(domain.PresenceStatus(user_id=msg.user_id, online=msg.online))
        except grpc.RpcError:
            # A mid-stream abort means we did NOT reach clean end-of-stream: the
            # list we hold is partial. Surfacing it as complete is the gallery
            # bug — report it incomplete so the handler 502s.
            return PresenceResult(statuses=[], incomplete=True)
        return PresenceResult(statuses=statuses, incomplete=False)
