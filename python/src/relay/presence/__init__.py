"""The companion-owned gRPC presence service (the real transport the G-GRPC
catch exercises) and the API-side client seam. Presence lives in Redis under
``presence:{userId}`` with a 60 s TTL set by the heartbeat; the unary RPC reads
one key, the streaming RPC emits exactly one status per requested user then
closes cleanly.
"""

KEY_PREFIX = "presence:"
