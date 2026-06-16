"""The naive (buggy) seam implementations — the default-shaped code an agent
ships when nobody pins the behavior. Each is sourced to a 05-gallery case.
"""

from __future__ import annotations

import contextlib
import time
from datetime import UTC, datetime

import grpc
from sqlalchemy import text

from relay import apierr, domain
from relay.app import MAX_SUMMARY_LENGTH, SUMMARY_SYSTEM_PROMPT, render_block  # noqa: F401
from relay.idgen import Factory
from relay.presence import presence_pb2, presence_pb2_grpc
from relay.seams import (
    ConversationCreateResult,
    MembershipCache,
    PresenceResult,
    SummaryModel,
    SummarySource,
    UnreadCounters,
)
from relay.store import Store


def _now() -> datetime:
    return datetime.now(UTC)


# ---- G-IDOR: load by id only, never call the participant predicate ----


class NaiveDmAccess:
    """Loads the conversation BY ID ONLY — ``is_participant`` exists and is
    correct, and is never called in this route ("correct logic, missing wiring")."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def get_for_participant(
        self, conversation_id: str, _user_id: str
    ) -> domain.Conversation | None:
        return self._store.conversation_by_id(conversation_id)


# ---- G-RACE: check-then-insert with no unique-conflict handling ----


class NaiveRaceConversationWriter:
    """Check-then-insert without the unique constraint's conflict handling. A
    test-only delay between the existence check and the insert widens the TOCTOU
    window deterministically without changing the SHAPE of the bug (still a
    missing unique-conflict handler)."""

    def __init__(self, store: Store, ids: Factory, widen_window: float = 0.05) -> None:
        self._store = store
        self._ids = ids
        self._widen = widen_window

    def create(self, user_lo: str, user_hi: str) -> ConversationCreateResult:
        existing = self._store.conversation_by_pair(user_lo, user_hi)
        if existing is not None:
            return ConversationCreateResult(conversation=existing, created=False)
        time.sleep(self._widen)  # widen the window (test-only; the bug is the missing handler)
        conversation = domain.Conversation(
            id=self._ids.create(), user_lo=user_lo, user_hi=user_hi, created_at=_now()
        )
        with self._store.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) "
                    "VALUES (:id, :lo, :hi, :created_at)"
                ),
                {
                    "id": conversation.id,
                    "lo": user_lo,
                    "hi": user_hi,
                    "created_at": conversation.created_at,
                },
            )
            for user_id in (user_lo, user_hi):
                conn.execute(
                    text(
                        "INSERT INTO dm_participants (conversation_id, user_id) "
                        "VALUES (:cid, :uid)"
                    ),
                    {"cid": conversation.id, "uid": user_id},
                )
        return ConversationCreateResult(conversation=conversation, created=True)


# ---- G-TX: three sequential saves, no transaction ----


class NaiveTxConversationWriter:
    """Three sequential saves, no transaction — the agent shape "call save three
    times". A mid-write failure leaves an orphan conversation + one participant."""

    def __init__(self, store: Store, ids: Factory) -> None:
        self._store = store
        self._ids = ids

    def create(self, user_lo: str, user_hi: str) -> ConversationCreateResult:
        conversation = domain.Conversation(
            id=self._ids.create(), user_lo=user_lo, user_hi=user_hi, created_at=_now()
        )
        # Each save is its own autocommitting transaction → no atomicity.
        with self._store.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) "
                    "VALUES (:id, :lo, :hi, :created_at)"
                ),
                {
                    "id": conversation.id,
                    "lo": user_lo,
                    "hi": user_hi,
                    "created_at": conversation.created_at,
                },
            )
        for user_id in (user_lo, user_hi):
            with self._store.begin() as conn:
                conn.execute(
                    text(
                        "INSERT INTO dm_participants (conversation_id, user_id) "
                        "VALUES (:cid, :uid)"
                    ),
                    {"cid": conversation.id, "uid": user_id},
                )
        return ConversationCreateResult(conversation=conversation, created=True)


# ---- G-BOLA-READ: ignore the private flag ----


class NaiveChannelReadGate:
    """Checks only that the channel EXISTS; ``private`` is never consulted for
    the caller, so a non-member reads a private channel's metadata and messages."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def authorize_read(self, channel_id: str, _user_id: str, _is_messages: bool) -> domain.Channel:
        channel = self._store.channel_by_id(channel_id)
        if channel is None:
            raise apierr.not_found_channel()
        return channel


# ---- G-BOLA-ROLE: check membership but skip the role compare ----


class NaiveChannelRoleGate:
    """Membership is checked (caller is a member) but ROLE is not — ``at_least``
    exists, unused on this route, so a plain member performs admin actions."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def authorize_role(
        self, channel_id: str, user_id: str, _min_role: domain.Role
    ) -> domain.ChannelMember:
        channel = self._store.channel_by_id(channel_id)
        if channel is None:
            raise apierr.not_found_channel()
        member = self._store.membership(channel_id, user_id)
        if member is None:
            if channel.private:
                raise apierr.not_found_channel()
            raise apierr.forbidden("channel:membership_required", "Membership is required.")
        return member  # role never compared


# ---- G-CACHE: write Postgres, forget the cache invalidation ----


class NaiveMembershipWriter:
    """Updates Postgres and FORGETS the Redis cache invalidation — the removed
    member keeps reading from the stale cache until the TTL expires."""

    def __init__(self, store: Store, _cache: MembershipCache) -> None:
        self._store = store

    def add(self, member: domain.ChannelMember) -> None:
        self._store.insert_member(member)

    def remove(self, channel_id: str, user_id: str) -> None:
        self._store.delete_member(channel_id, user_id)  # no cache invalidation


# ---- G-KAFKA producer: fire-and-forget publish ----


class NaiveFireAndForgetPublisher:
    """Fire-and-forget publish: the result is never awaited/confirmed. Broker
    down → 201 anyway, the event silently lost, feeds never update."""

    def publish(self, _event: domain.MessagePosted) -> None:
        return  # never confirmed, never raised — the loss is silent


# ---- G-KAFKA consumer: unconditional insert + unconditional increment ----


class NaiveFeedProjector:
    """Non-idempotent fan-out: unconditional feed insert + unconditional counter
    increment. Event redelivery → duplicate feed entry attempt and/or a counter
    diverging from the feed."""

    def __init__(self, store: Store, unread: UnreadCounters, ids: Factory) -> None:
        self._store = store
        self._unread = unread
        self._ids = ids

    def apply(self, event: domain.MessagePosted) -> None:
        for member_id in self._store.member_ids_except(event.channel_id, event.sender_id):
            self._unread.increment(member_id, event.channel_id)  # increment FIRST, unconditional
            entry = domain.FeedEntry(
                id=self._ids.create(),
                user_id=member_id,
                channel_id=event.channel_id,
                message_id=event.message_id,
                sender_id=event.sender_id,
                preview=event.preview,
                created_at=event.posted_at,
            )
            # The duplicate insert is swallowed; the counter has already moved.
            with contextlib.suppress(Exception):
                self._store.insert_feed_entry(entry)


# ---- G-RABBIT: insert-or-crash, never handles the duplicate ----


class NaiveNotificationRecorder:
    """Inserts unconditionally and acks on success — never HANDLES the duplicate.
    On redelivery the insert hits UNIQUE(dm_message_id), the worker raises and
    nack-requeues, and after the delivery limit the duplicate dead-letters."""

    def __init__(self, store: Store, ids: Factory) -> None:
        self._store = store
        self._ids = ids

    def record(self, job: domain.NotificationJob) -> None:
        notification = domain.Notification(
            id=self._ids.create(),
            user_id=job.recipient_id,
            dm_message_id=job.dm_message_id,
            conversation_id=job.conversation_id,
            sender_id=job.sender_id,
            preview=job.preview,
            created_at=_now(),
        )
        self._store.insert_notification(notification)  # raises on the duplicate → crash-loop


# ---- G-S3: possession of the id IS access ----


class NaiveAttachmentAccess:
    """Looks the attachment up BY ID and returns it — possession of the id is
    access; channel membership never consulted."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def authorize(self, attachment_id: str, _user_id: str) -> domain.Attachment:
        attachment = self._store.attachment_by_id(attachment_id)
        if attachment is None:
            raise apierr.not_found_attachment()
        return attachment  # membership never checked


# ---- G-LLM: concatenate raw text into the instruction prompt; no output validation ----


class NaiveSummarizer:
    """(a) Concatenates raw message text into the INSTRUCTION prompt; a message
    containing "ignore previous instructions…" becomes instructions. (b) Returns
    the model output straight to the client, unvalidated."""

    def __init__(self, model: SummaryModel) -> None:
        self._model = model

    def summarize(self, sources: list[SummarySource]) -> str:
        # (a) raw text concatenated into the instruction text — no delimited data blocks.
        instruction = "Summarize this conversation: " + " ".join(
            f"{s.handle}: {s.text}" for s in sources
        )
        request = domain.SummaryRequest(system_prompt=instruction, message_blocks=[])
        return self._model.complete(request)  # (b) returned unvalidated


# ---- G-HTTP: no timeout, no guard ----


class NaiveLinkPreviewer:
    """Awaits the unfurl call with NO timeout and NO try/except — a slow or
    500ing unfurl service makes message posting hang or 500."""

    def __init__(self, base_url: str) -> None:
        import httpx

        self._http = httpx.Client(timeout=None)  # no timeout
        self._base_url = base_url.rstrip("/")

    def preview(self, url: str) -> str | None:
        response = self._http.get(self._base_url + "/unfurl", params={"url": url})
        response.raise_for_status()  # a 5xx escapes as an error → 500
        return response.json()["title"]


# ---- G-GRPC: swallow the mid-stream error, return what arrived ----


class NaivePresenceClient:
    """Collects messages in a try/except that SWALLOWS the stream error and
    returns whatever arrived — a partial member list presented as complete."""

    def __init__(self, channel: grpc.Channel) -> None:
        self._stub = presence_pb2_grpc.PresenceStub(channel)

    def user_presence(self, user_id: str) -> bool:
        return self._stub.GetPresence(presence_pb2.GetPresenceRequest(user_id=user_id)).online

    def channel_presence(self, user_ids: list[str]) -> PresenceResult:
        statuses: list[domain.PresenceStatus] = []
        try:
            for msg in self._stub.StreamChannelPresence(
                presence_pb2.StreamChannelPresenceRequest(user_ids=user_ids)
            ):
                statuses.append(domain.PresenceStatus(user_id=msg.user_id, online=msg.online))
        except grpc.RpcError:
            pass  # swallowed — the partial list is returned as if complete
        return PresenceResult(statuses=statuses, incomplete=False)
