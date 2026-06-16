"""Relay's entities and the pure predicates the gallery's honesty notes call out
as the legitimate home of unit tests (participant check, role ordering, preview
truncation). Whether a route WIRES these in is a system property the component
tests verify; that the predicates are correct is unit territory.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from datetime import datetime


class Role(enum.IntEnum):
    """A channel membership role, ordered owner > admin > member."""

    MEMBER = 0
    ADMIN = 1
    OWNER = 2

    def at_least(self, minimum: Role) -> bool:
        """Whether self is at least as privileged as ``minimum`` — the pure
        ordering predicate the G-BOLA-ROLE honesty note unit-tests."""
        return self >= minimum

    @property
    def label(self) -> str:
        return {Role.OWNER: "owner", Role.ADMIN: "admin", Role.MEMBER: "member"}[self]

    @staticmethod
    def parse(value: str) -> Role:
        return {"owner": Role.OWNER, "admin": Role.ADMIN, "member": Role.MEMBER}[value]


@dataclass
class User:
    id: str
    handle: str
    display_name: str
    created_at: datetime


@dataclass
class Conversation:
    """A 1:1 DM; the pair stored normalized (user_lo < user_hi)."""

    id: str
    user_lo: str
    user_hi: str
    created_at: datetime

    def is_participant(self, user_id: str) -> bool:
        """The DM access predicate — pure logic, the G-IDOR honesty note's unit
        target. A read path that never calls it is the bug, not this function."""
        return self.user_lo == user_id or self.user_hi == user_id


def normalize_pair(a: str, b: str) -> tuple[str, str]:
    """Return the two ids in lexicographic order (lo, hi)."""
    return (a, b) if a < b else (b, a)


@dataclass
class DmMessage:
    id: str
    conversation_id: str
    sender_id: str
    text: str
    created_at: datetime


@dataclass
class Channel:
    id: str
    name: str
    private: bool
    created_at: datetime


@dataclass
class ChannelMember:
    channel_id: str
    user_id: str
    role: Role
    joined_at: datetime


@dataclass
class ChannelMessage:
    id: str
    channel_id: str
    sender_id: str
    text: str
    link_preview_title: str | None
    created_at: datetime


@dataclass
class Attachment:
    """Metadata for a stored file; access derives from channel membership,
    NEVER from possession of storage_key (G-S3)."""

    id: str
    channel_id: str
    uploader_id: str
    message_id: str | None
    filename: str
    size_bytes: int
    storage_key: str
    created_at: datetime


@dataclass
class Notification:
    id: str
    user_id: str
    dm_message_id: str
    conversation_id: str
    sender_id: str
    preview: str
    created_at: datetime


@dataclass
class FeedEntry:
    id: str
    user_id: str
    channel_id: str
    message_id: str
    sender_id: str
    preview: str
    created_at: datetime


@dataclass
class MessagePosted:
    """The Kafka event (topic message-posted, key = channel_id) fanned out to
    members' feeds + unread counters."""

    message_id: str
    channel_id: str
    sender_id: str
    preview: str
    posted_at: datetime


@dataclass
class NotificationJob:
    """The RabbitMQ job (queue notify.dm) the worker turns into a notification
    row, exactly once per DM message under at-least-once redelivery."""

    dm_message_id: str
    conversation_id: str
    sender_id: str
    recipient_id: str
    preview: str


@dataclass
class PresenceStatus:
    user_id: str
    online: bool


@dataclass
class SummaryRequest:
    """What the app hands the SummaryModel: a constant system prompt plus the
    messages as already-rendered, delimited DATA blocks. The fake verifies the
    system prompt equals the pinned constant and that hostile text appears ONLY
    inside a block (G-LLM)."""

    system_prompt: str
    message_blocks: list[str] = field(default_factory=list)


PREVIEW_MAX_LENGTH = 100


def preview(text: str) -> str:
    """Truncate to the first 100 characters — the pure function the gallery
    honesty notes unit-test; the component tests only assert it is wired into
    the event/notification paths."""
    return text if len(text) <= PREVIEW_MAX_LENGTH else text[:PREVIEW_MAX_LENGTH]


def first_url(text: str) -> str | None:
    """The first http(s):// token in text, or None — the trigger for link unfurl."""
    for token in text.split():
        if token.startswith(("http://", "https://")):
            return token
    return None
