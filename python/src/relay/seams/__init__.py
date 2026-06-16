"""The narrow interfaces each gallery case hides behind (05-gallery §0.4).

The app's handlers depend on these Protocols, never on concrete types. The
CORRECT implementations live in ``relay.app.seams_impl`` (wired in
``relay.app.build``); the NAIVE variants live under ``tests/naive`` and are
injected through the SAME seam — FastAPI ``app.dependency_overrides`` — scoped
to one test. That a 404/403 decision is a property of the *assembled route*
(not of a mock) is exactly what makes the catching tests catch and the lying
tests lie.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from relay import domain


@dataclass
class ConversationCreateResult:
    """Carries the conversation and whether this call created it (201) or found
    an existing one (200, idempotent)."""

    conversation: domain.Conversation
    created: bool


@dataclass
class PresenceResult:
    """The channel-presence outcome: the per-member statuses, or ``incomplete``
    when the stream errored mid-way (→ 502, never a partial list as complete)."""

    statuses: list[domain.PresenceStatus] = field(default_factory=list)
    incomplete: bool = False


@dataclass
class SummarySource:
    """One channel message handed to the Summarizer (sender handle + text)."""

    handle: str
    text: str


@runtime_checkable
class DmAccess(Protocol):
    """The G-IDOR seam: participant-scoped conversation read. Returns None when
    the caller is not a participant (or the conversation is absent), and the
    route 404s — hiding existence. The naive variant loads by id only."""

    def get_for_participant(
        self, conversation_id: str, user_id: str
    ) -> domain.Conversation | None: ...


@runtime_checkable
class ConversationWriter(Protocol):
    """The G-RACE / G-TX seam: a transactional, unique-conflict-handling create.
    Concurrent creates for one pair resolve to a single row (RACE); a mid-write
    failure leaves nothing behind (TX)."""

    def create(self, user_lo: str, user_hi: str) -> ConversationCreateResult: ...


@runtime_checkable
class ChannelReadGate(Protocol):
    """The G-BOLA-READ seam: the 404/403 visibility split for reading a
    channel's metadata/messages. The naive variant ignores the private flag."""

    def authorize_read(
        self, channel_id: str, user_id: str, is_messages: bool
    ) -> domain.Channel: ...


@runtime_checkable
class ChannelRoleGate(Protocol):
    """The G-BOLA-ROLE seam: membership AND role check for admin actions.
    Returns the caller's membership so the handler can apply finer rules
    (e.g. kicking an admin). The naive variant skips the role compare."""

    def authorize_role(
        self, channel_id: str, user_id: str, min_role: domain.Role
    ) -> domain.ChannelMember: ...


@runtime_checkable
class MembershipWriter(Protocol):
    """The G-CACHE seam: a membership write coupled to cache invalidation. The
    naive variant writes Postgres and forgets the Redis invalidation."""

    def add(self, member: domain.ChannelMember) -> None: ...

    def remove(self, channel_id: str, user_id: str) -> None: ...


@runtime_checkable
class MessagePostedPublisher(Protocol):
    """The G-KAFKA producer seam: publish awaiting broker confirmation (broker
    down → error → 503, message not persisted). The naive variant fires and
    forgets."""

    def publish(self, event: domain.MessagePosted) -> None: ...


@runtime_checkable
class FeedProjector(Protocol):
    """The G-KAFKA consumer seam: idempotent feed insert + increment-on-first-
    insert. The naive variant inserts and increments unconditionally."""

    def apply(self, event: domain.MessagePosted) -> None: ...


@runtime_checkable
class NotificationRecorder(Protocol):
    """The G-RABBIT seam: insert treating a duplicate (unique violation) as
    success so the worker acks. The naive variant never handles the duplicate
    and crashes."""

    def record(self, job: domain.NotificationJob) -> None: ...


@runtime_checkable
class PresenceClient(Protocol):
    """The G-GRPC seam: consume the presence stream to clean end; a mid-stream
    error sets ``incomplete``. The naive variant swallows the error and returns
    what arrived."""

    def user_presence(self, user_id: str) -> bool: ...

    def channel_presence(self, user_ids: list[str]) -> PresenceResult: ...


@runtime_checkable
class Heartbeats(Protocol):
    """Marks a user online (TTL 60 s) by writing the SAME Redis key the presence
    gRPC service reads, so a heartbeat is observable through both presence
    paths."""

    def mark(self, user_id: str) -> None: ...


@runtime_checkable
class LinkPreviewer(Protocol):
    """The G-HTTP seam: fetch a link title with timeout + circuit breaker;
    failure degrades to no title (never escapes). Returns None when the unfurl
    failed/degraded/breaker-open. The naive variant has no timeout/guard."""

    def preview(self, url: str) -> str | None: ...


@runtime_checkable
class SummaryModel(Protocol):
    """The LLM port (the canonical FAKE): the app never builds a prompt string
    inline — everything crosses this port. The fake verifies the interaction
    (the captured request)."""

    def complete(self, request: domain.SummaryRequest) -> str: ...


@runtime_checkable
class Summarizer(Protocol):
    """The G-LLM seam: assembles the model request and VALIDATES the output. The
    correct implementation keeps instructions and user content separated (prompt
    injection) and rejects contract-violating output with 502 (never forwards
    it). The naive variant concatenates raw message text into the instruction
    prompt and returns output unvalidated."""

    def summarize(self, sources: list[SummarySource]) -> str: ...


@runtime_checkable
class AttachmentAccess(Protocol):
    """The G-S3 seam: download authorization derives from the attachment's
    CHANNEL MEMBERSHIP, never from possession of the id or storage key. Unknown
    id and private-channel non-member return the same existence-hiding 404;
    public non-member → 403. The naive variant looks up by id and returns it."""

    def authorize(self, attachment_id: str, user_id: str) -> domain.Attachment: ...


@runtime_checkable
class AttachmentStore(Protocol):
    """The object-store port (S3). Bytes live behind an opaque storage key;
    authorization NEVER reads key possession (G-S3)."""

    def put(self, key: str, data: bytes) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete_all(self) -> None: ...


@runtime_checkable
class NotificationJobs(Protocol):
    """Publishes a DM notification job (RabbitMQ) after the message commits,
    awaiting the broker's publisher confirmation."""

    def enqueue(self, job: domain.NotificationJob) -> None: ...


@runtime_checkable
class MembershipCache(Protocol):
    """The Redis authorization fast-path + its invalidation hook."""

    def is_member(self, channel_id: str, user_id: str) -> tuple[bool, bool]:
        """Returns (cached, member): whether the answer came from cache, and the
        membership verdict when it did."""
        ...

    def remember(self, channel_id: str, member_ids: list[str]) -> None: ...

    def invalidate(self, channel_id: str) -> None: ...


@runtime_checkable
class UnreadCounters(Protocol):
    """The Redis per-channel unread counter."""

    def increment(self, user_id: str, channel_id: str) -> None: ...

    def reset(self, user_id: str, channel_id: str) -> None: ...

    def for_user(self, user_id: str) -> dict[str, int]: ...
