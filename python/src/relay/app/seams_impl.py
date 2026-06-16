"""The CORRECT seam implementations (05-gallery §0.4).

Each is the participant-/role-/transaction-/idempotency-correct wiring the
handler depends on. The naive variants (under ``tests/naive``) implement the
same Protocol with the default-shaped bug and are injected through
``app.dependency_overrides`` — never present in ``src/``.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import text

from relay import apierr, domain
from relay.app import MAX_SUMMARY_LENGTH, SUMMARY_SYSTEM_PROMPT, render_block
from relay.idgen import Factory
from relay.seams import (
    ConversationCreateResult,
    MembershipCache,
    SummaryModel,
    SummarySource,
    UnreadCounters,
)
from relay.store import Store, is_unique_violation


def _now() -> datetime:
    return datetime.now(UTC)


# ---- G-IDOR: correct DM access ----


class DmAccess:
    """Load the conversation, then APPLY the participant predicate. A
    non-participant gets None → the route 404s. The naive variant skips it."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def get_for_participant(self, conversation_id: str, user_id: str) -> domain.Conversation | None:
        conversation = self._store.conversation_by_id(conversation_id)
        if conversation is None or not conversation.is_participant(user_id):
            return None
        return conversation


# ---- G-RACE / G-TX: correct transactional conversation writer ----


class ConversationWriter:
    """One transaction inserts the conversation + both participant rows, and a
    unique-pair violation (the concurrent loser) is recovered by reading back
    the winner's row. Timing-independent — no test hook needed."""

    def __init__(self, store: Store, ids: Factory) -> None:
        self._store = store
        self._ids = ids

    def create(self, user_lo: str, user_hi: str) -> ConversationCreateResult:
        existing = self._store.conversation_by_pair(user_lo, user_hi)
        if existing is not None:
            return ConversationCreateResult(conversation=existing, created=False)

        conversation = domain.Conversation(
            id=self._ids.create(), user_lo=user_lo, user_hi=user_hi, created_at=_now()
        )
        try:
            self._insert_atomically(conversation)
        except Exception as err:
            if is_unique_violation(err):
                winner = self._store.conversation_by_pair(user_lo, user_hi)
                if winner is not None:
                    return ConversationCreateResult(conversation=winner, created=False)
            raise
        return ConversationCreateResult(conversation=conversation, created=True)

    def _insert_atomically(self, conversation: domain.Conversation) -> None:
        with self._store.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) "
                    "VALUES (:id, :lo, :hi, :created_at)"
                ),
                {
                    "id": conversation.id,
                    "lo": conversation.user_lo,
                    "hi": conversation.user_hi,
                    "created_at": conversation.created_at,
                },
            )
            for user_id in (conversation.user_lo, conversation.user_hi):
                conn.execute(
                    text(
                        "INSERT INTO dm_participants (conversation_id, user_id) "
                        "VALUES (:cid, :uid)"
                    ),
                    {"cid": conversation.id, "uid": user_id},
                )


# ---- G-BOLA-READ: correct channel read gate ----


class ChannelReadGate:
    """The 404/403 visibility split. Private + non-member → 404 (existence
    hidden). Public + non-member → metadata 200 but 403 for messages. The naive
    variant never consults the private flag for the caller.

    Membership is resolved through the Redis cache fast-path (members:{channelId})
    with a Postgres fallback that warms the cache. This is the surface the
    G-CACHE case hides on: if a membership write forgets to invalidate the
    cache, a removed member's next read is granted from the stale set (the bug);
    the correct MembershipWriter invalidates, so the next read misses the cache
    and Postgres denies it."""

    def __init__(self, store: Store, cache: MembershipCache) -> None:
        self._store = store
        self._cache = cache

    def authorize_read(self, channel_id: str, user_id: str, is_messages: bool) -> domain.Channel:
        channel = self._store.channel_by_id(channel_id)
        if channel is None:
            raise apierr.not_found_channel()
        if self._is_member(channel_id, user_id):
            return channel
        if channel.private:
            raise apierr.not_found_channel()
        if is_messages:
            raise apierr.forbidden(
                "channel:membership_required", "Membership is required to read messages."
            )
        return channel

    def _is_member(self, channel_id: str, user_id: str) -> bool:
        cached, member = self._cache.is_member(channel_id, user_id)
        if cached:
            return member
        member_ids = self._store.member_ids(channel_id)
        self._cache.remember(channel_id, member_ids)
        return user_id in member_ids


# ---- G-BOLA-ROLE: correct channel role gate ----


class ChannelRoleGate:
    """Membership AND the role compare. A plain member attempting an admin action
    gets 403 (visible-but-forbidden); a non-member gets 404 (private) / 403
    (public). The naive variant checks membership but skips the role."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def authorize_role(
        self, channel_id: str, user_id: str, min_role: domain.Role
    ) -> domain.ChannelMember:
        channel = self._store.channel_by_id(channel_id)
        if channel is None:
            raise apierr.not_found_channel()
        member = self._store.membership(channel_id, user_id)
        if member is None:
            if channel.private:
                raise apierr.not_found_channel()
            raise apierr.forbidden("channel:membership_required", "Membership is required.")
        if not member.role.at_least(min_role):
            raise apierr.forbidden(
                "channel:role:forbidden", "Your role does not permit this action."
            )
        return member


# ---- G-CACHE: correct membership writer (write + invalidate) ----


class MembershipWriter:
    """A membership write (add/remove) coupled to invalidating the Redis
    membership cache, so a removed member's next read is denied immediately. The
    naive variant writes Postgres and forgets the invalidation."""

    def __init__(self, store: Store, cache: MembershipCache) -> None:
        self._store = store
        self._cache = cache

    def add(self, member: domain.ChannelMember) -> None:
        self._store.insert_member(member)
        self._cache.invalidate(member.channel_id)

    def remove(self, channel_id: str, user_id: str) -> None:
        self._store.delete_member(channel_id, user_id)
        self._cache.invalidate(channel_id)


# ---- G-KAFKA consumer: correct feed projector ----


class FeedProjector:
    """Idempotent per (user, message). The UNIQUE (user_id, message_id)
    constraint is the backstop, and the unread counter is incremented ONLY on a
    first successful insert — so feed and counter never diverge under
    redelivery. The naive variant inserts and increments unconditionally."""

    def __init__(self, store: Store, unread: UnreadCounters, ids: Factory) -> None:
        self._store = store
        self._unread = unread
        self._ids = ids

    def apply(self, event: domain.MessagePosted) -> None:
        for member_id in self._store.member_ids_except(event.channel_id, event.sender_id):
            entry = domain.FeedEntry(
                id=self._ids.create(),
                user_id=member_id,
                channel_id=event.channel_id,
                message_id=event.message_id,
                sender_id=event.sender_id,
                preview=event.preview,
                created_at=event.posted_at,
            )
            try:
                self._store.insert_feed_entry(entry)
            except Exception as err:
                if is_unique_violation(err):
                    continue  # already projected — do NOT increment again
                raise
            self._unread.increment(member_id, event.channel_id)


# ---- G-RABBIT: correct notification recorder ----


class NotificationRecorder:
    """Insert, treating the UNIQUE(dm_message_id) violation (a redelivered
    duplicate) as SUCCESS so the worker acks. A genuine failure (poison job —
    unresolvable recipient FK) bubbles up to be retried then dead-lettered. The
    naive variant never handles the duplicate and crash-loops into the DLQ."""

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
        try:
            self._store.insert_notification(notification)
        except Exception as err:
            if is_unique_violation(err):
                return  # redelivered duplicate — already recorded. Success → ack.
            raise


# ---- G-LLM: correct summarizer ----


class Summarizer:
    """Instructions ONLY in the system prompt, messages ONLY as delimited data
    blocks, and the model output VALIDATED (non-empty, ≤ 2000 chars) before
    returning — else 502, never forwarding garbage. The naive variant
    concatenates raw text into the instruction prompt and returns it unvalidated."""

    def __init__(self, model: SummaryModel) -> None:
        self._model = model

    def summarize(self, sources: list[SummarySource]) -> str:
        blocks = [render_block(src.handle, src.text) for src in sources]
        request = domain.SummaryRequest(
            system_prompt=SUMMARY_SYSTEM_PROMPT, message_blocks=blocks
        )
        output = self._model.complete(request)
        if not output.strip() or len(output) > MAX_SUMMARY_LENGTH:
            raise apierr.upstream(
                "summary:invalid_output", "The model violated the summary output contract."
            )
        return output


# ---- G-S3: correct attachment access ----


class AttachmentAccess:
    """Resolve the attachment's channel and require the caller's MEMBERSHIP —
    never key possession. Unknown id and private-channel non-member both 404
    (byte-identical body); public-channel non-member gets 403. Naive returns by
    id."""

    def __init__(self, store: Store) -> None:
        self._store = store

    def authorize(self, attachment_id: str, user_id: str) -> domain.Attachment:
        attachment = self._store.attachment_by_id(attachment_id)
        if attachment is None:
            raise apierr.not_found_attachment()
        member = self._store.membership(attachment.channel_id, user_id)
        if member is not None:
            return attachment
        channel = self._store.channel_by_id(attachment.channel_id)
        if channel is not None and channel.private:
            raise apierr.not_found_attachment()
        raise apierr.forbidden(
            "channel:membership_required", "Membership is required to download this attachment."
        )
