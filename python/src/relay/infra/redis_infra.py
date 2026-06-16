"""Redis-backed ports: membership cache, unread counters, heartbeats."""

from __future__ import annotations

import redis

from relay.presence import KEY_PREFIX

MEMBERSHIP_TTL = 300  # seconds
PRESENCE_TTL = 60  # seconds


def _members_key(channel_id: str) -> str:
    return "members:" + channel_id


class MembershipCache:
    """The Redis membership fast-path (members:{channelId}, a set) coupled to
    invalidation on membership writes. Its coherence with Postgres is the
    G-CACHE property."""

    def __init__(self, client: redis.Redis) -> None:
        self._redis = client

    def is_member(self, channel_id: str, user_id: str) -> tuple[bool, bool]:
        key = _members_key(channel_id)
        if self._redis.exists(key) == 0:
            return (False, False)
        return (True, bool(self._redis.sismember(key, user_id)))

    def remember(self, channel_id: str, member_ids: list[str]) -> None:
        key = _members_key(channel_id)
        pipe = self._redis.pipeline(transaction=True)
        pipe.delete(key)
        if member_ids:
            pipe.sadd(key, *member_ids)
            pipe.expire(key, MEMBERSHIP_TTL)
        pipe.execute()

    def invalidate(self, channel_id: str) -> None:
        self._redis.delete(_members_key(channel_id))


def _unread_key(user_id: str, channel_id: str) -> str:
    return f"unread:{user_id}:{channel_id}"


class UnreadCounters:
    """The Redis per-channel unread counter (unread:{userId}:{channelId})."""

    def __init__(self, client: redis.Redis) -> None:
        self._redis = client

    def increment(self, user_id: str, channel_id: str) -> None:
        self._redis.incr(_unread_key(user_id, channel_id))

    def reset(self, user_id: str, channel_id: str) -> None:
        self._redis.delete(_unread_key(user_id, channel_id))

    def for_user(self, user_id: str) -> dict[str, int]:
        prefix = f"unread:{user_id}:"
        out: dict[str, int] = {}
        for key in self._redis.scan_iter(match=prefix + "*", count=100):
            channel_id = key[len(prefix):]
            raw = self._redis.get(key)
            out[channel_id] = int(raw) if raw is not None else 0
        return out


class Heartbeats:
    """Writes the presence key the gRPC service reads (presence:{userId}, 60 s TTL)."""

    def __init__(self, client: redis.Redis) -> None:
        self._redis = client

    def mark(self, user_id: str) -> None:
        self._redis.set(KEY_PREFIX + user_id, "1", ex=PRESENCE_TTL)
