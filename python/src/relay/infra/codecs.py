"""Event / job payload codecs — pure functions (unit territory).

The wire contract is JSON with the cross-language field names and an RFC-3339
``postedAt`` (the same contract the other four languages serialize), so a Python
producer and, say, a Go consumer would interoperate.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime

from relay import domain


def _to_rfc3339(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _from_rfc3339(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def serialize_message_posted(event: domain.MessagePosted) -> bytes:
    return json.dumps(
        {
            "messageId": event.message_id,
            "channelId": event.channel_id,
            "senderId": event.sender_id,
            "preview": event.preview,
            "postedAt": _to_rfc3339(event.posted_at),
        }
    ).encode()


def deserialize_message_posted(raw: bytes) -> domain.MessagePosted:
    data = json.loads(raw)
    return domain.MessagePosted(
        message_id=data["messageId"],
        channel_id=data["channelId"],
        sender_id=data["senderId"],
        preview=data["preview"],
        posted_at=_from_rfc3339(data["postedAt"]),
    )


def serialize_job(job: domain.NotificationJob) -> bytes:
    return json.dumps(
        {
            "dmMessageId": job.dm_message_id,
            "conversationId": job.conversation_id,
            "senderId": job.sender_id,
            "recipientId": job.recipient_id,
            "preview": job.preview,
        }
    ).encode()


def deserialize_job(raw: bytes) -> domain.NotificationJob:
    data = json.loads(raw)
    return domain.NotificationJob(
        dm_message_id=data["dmMessageId"],
        conversation_id=data["conversationId"],
        sender_id=data["senderId"],
        recipient_id=data["recipientId"],
        preview=data["preview"],
    )
