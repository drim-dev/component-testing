"""Relay's composition root container + the FastAPI dependency providers.

The whole DI story is one dataclass: ``Deps`` holds every seam (05-gallery §0.4)
plus the infrastructure ports. ``build_app(deps)`` wires the FastAPI app.

The seam is FastAPI ``app.dependency_overrides``: handlers depend on a seam via
``Depends(provide_dm_access)`` and friends, which by default read the field off
``Deps``. A test injects a naive variant by overriding exactly ONE provider —
the clean analog of .NET ``RemoveAll``+re-register, Go's constructor swap,
Nest's DI override, Spring's ``@Primary`` bean.
"""

from __future__ import annotations

from dataclasses import dataclass

from fastapi import Request

from relay import seams
from relay.idgen import Factory
from relay.store import Store


@dataclass
class Deps:
    """The complete dependency set the app is constructed from. Each field is a
    seam (an injectable bug) or an infrastructure port. The CORRECT
    implementations are assembled in ``build``; a test overrides exactly one
    provider with its naive variant."""

    store: Store
    ids: Factory

    # Gallery seams (the injectable bugs).
    dm_access: seams.DmAccess
    conversation_writer: seams.ConversationWriter
    channel_read_gate: seams.ChannelReadGate
    channel_role_gate: seams.ChannelRoleGate
    membership_writer: seams.MembershipWriter
    publisher: seams.MessagePostedPublisher
    notification_recorder: seams.NotificationRecorder
    presence: seams.PresenceClient
    link_previewer: seams.LinkPreviewer
    attachment_access: seams.AttachmentAccess

    # Infrastructure ports.
    cache: seams.MembershipCache
    unread: seams.UnreadCounters
    jobs: seams.NotificationJobs
    store3: seams.AttachmentStore
    summary_model: seams.SummaryModel
    summarizer: seams.Summarizer
    feed: seams.FeedProjector
    heartbeats: seams.Heartbeats


def get_deps(request: Request) -> Deps:
    return request.app.state.deps


# The seam providers. Each defaults to the field off Deps; a test overrides ONE
# of these via app.dependency_overrides to inject a naive variant.


def provide_store(request: Request) -> Store:
    return get_deps(request).store


def provide_dm_access(request: Request) -> seams.DmAccess:
    return get_deps(request).dm_access


def provide_conversation_writer(request: Request) -> seams.ConversationWriter:
    return get_deps(request).conversation_writer


def provide_channel_read_gate(request: Request) -> seams.ChannelReadGate:
    return get_deps(request).channel_read_gate


def provide_channel_role_gate(request: Request) -> seams.ChannelRoleGate:
    return get_deps(request).channel_role_gate


def provide_membership_writer(request: Request) -> seams.MembershipWriter:
    return get_deps(request).membership_writer


def provide_publisher(request: Request) -> seams.MessagePostedPublisher:
    return get_deps(request).publisher


def provide_presence(request: Request) -> seams.PresenceClient:
    return get_deps(request).presence


def provide_link_previewer(request: Request) -> seams.LinkPreviewer:
    return get_deps(request).link_previewer


def provide_attachment_access(request: Request) -> seams.AttachmentAccess:
    return get_deps(request).attachment_access


def provide_cache(request: Request) -> seams.MembershipCache:
    return get_deps(request).cache


def provide_unread(request: Request) -> seams.UnreadCounters:
    return get_deps(request).unread


def provide_jobs(request: Request) -> seams.NotificationJobs:
    return get_deps(request).jobs


def provide_store3(request: Request) -> seams.AttachmentStore:
    return get_deps(request).store3


def provide_summarizer(request: Request) -> seams.Summarizer:
    return get_deps(request).summarizer


def provide_heartbeats(request: Request) -> seams.Heartbeats:
    return get_deps(request).heartbeats
