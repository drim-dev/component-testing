"""The companion-owned gRPC presence neighbour service and the API-side client
seam. Presence lives in Redis under ``presence:{userId}`` with a 60 s TTL set by
the heartbeat; the unary RPC reads one key, the streaming RPC emits exactly one
status per requested user then closes cleanly. In a component test of the Relay
API this neighbour is stubbed (see harness), not run for real.
"""

KEY_PREFIX = "presence:"
