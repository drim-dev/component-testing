"""Boots a STUB presence gRPC service on an ephemeral 127.0.0.1 port over a real
socket, so the API still consumes presence through genuine gRPC (cleartext h2c)
— the transport-agnostic proof — without dragging the neighbour's own
dependencies (its Redis) into the test.

Presence is a NEIGHBOUR service, so in a component test of the Relay API it is
stubbed, not run for real. set_online = program the canned answer. fail_stream_after
= arm the stream to fail after N (the deterministic partial-stream probe).
reset = clear the online set and the fault flag.
"""

from __future__ import annotations

import threading
from concurrent import futures

import grpc

from relay.presence import presence_pb2, presence_pb2_grpc


class _PresenceStub(presence_pb2_grpc.PresenceServicer):
    """A canned-response stand-in for the neighbour presence service: answers the
    unary and streaming RPCs from an in-memory online set, with a test-only fault
    that aborts the stream after N messages (the deterministic partial-stream
    probe for G-GRPC). No Redis, no neighbour dependencies — just the contract the
    Relay API consumes.
    """

    def __init__(self) -> None:
        self._online: set[str] = set()
        self._fail_after: int | None = None
        self._lock = threading.Lock()

    def set_online(self, user_id: str) -> None:
        with self._lock:
            self._online.add(user_id)

    def fail_stream_after(self, n: int) -> None:
        with self._lock:
            self._fail_after = n

    def reset(self) -> None:
        with self._lock:
            self._online.clear()
            self._fail_after = None

    def _is_online(self, user_id: str) -> bool:
        with self._lock:
            return user_id in self._online

    def _limit(self) -> int | None:
        with self._lock:
            return self._fail_after

    def GetPresence(  # noqa: N802 (gRPC stub name)
        self, request: presence_pb2.GetPresenceRequest, context: grpc.ServicerContext
    ) -> presence_pb2.PresenceStatus:
        return presence_pb2.PresenceStatus(
            user_id=request.user_id, online=self._is_online(request.user_id)
        )

    def StreamChannelPresence(  # noqa: N802 (gRPC stub name)
        self,
        request: presence_pb2.StreamChannelPresenceRequest,
        context: grpc.ServicerContext,
    ):
        limit = self._limit()
        for index, user_id in enumerate(request.user_ids):
            if limit is not None and index >= limit:
                context.abort(
                    grpc.StatusCode.UNAVAILABLE,
                    "presence stream fault (test-only): aborting mid-stream",
                )
            yield presence_pb2.PresenceStatus(user_id=user_id, online=self._is_online(user_id))


class PresenceHarness:
    def __init__(self) -> None:
        self._stub = _PresenceStub()
        self._server: grpc.Server | None = None
        self._addr = ""

    def start(self) -> None:
        self._server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
        presence_pb2_grpc.add_PresenceServicer_to_server(self._stub, self._server)
        port = self._server.add_insecure_port("127.0.0.1:0")
        self._addr = f"127.0.0.1:{port}"
        self._server.start()

    @property
    def addr(self) -> str:
        return self._addr

    def set_online(self, user_id: str) -> None:
        """Program the stub: mark a user online in its canned answer."""
        self._stub.set_online(user_id)

    def fail_stream_after(self, n: int) -> None:
        """Arm the partial-stream fault: the next stream emits n statuses then aborts."""
        self._stub.fail_stream_after(n)

    def reset(self) -> None:
        self._stub.reset()

    def stop(self) -> None:
        if self._server is not None:
            self._server.stop(grace=2).wait()
