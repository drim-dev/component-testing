"""Channel endpoints: create/list/get, join, member management, messages, read."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Request, Response

from relay import apierr, domain, paging, seams
from relay.app.deps import (
    provide_cache,
    provide_channel_read_gate,
    provide_channel_role_gate,
    provide_link_previewer,
    provide_membership_writer,
    provide_publisher,
    provide_store,
    provide_unread,
)
from relay.app.identity import require_user
from relay.app.requests import read_json
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()


def _now() -> datetime:
    return datetime.now(UTC)


def _channel_dto(channel: domain.Channel, member_count: int | None = None) -> dict[str, Any]:
    dto: dict[str, Any] = {
        "id": channel.id,
        "name": channel.name,
        "private": channel.private,
        "createdAt": channel.created_at,
    }
    if member_count is not None:
        dto["memberCount"] = member_count
    return dto


def _membership_dto(member: domain.ChannelMember) -> dict[str, Any]:
    return {
        "channelId": member.channel_id,
        "userId": member.user_id,
        "role": member.role.label,
        "joinedAt": member.joined_at,
    }


@router.post("/channels")
async def create_channel(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
):
    body = await read_json(request)
    name = body.get("name")
    private = body.get("private", False)
    if not isinstance(name, str) or not (1 <= len(name) <= 100):
        raise apierr.invalid("channel:name:invalid", "name must be 1–100 chars.")

    channel = domain.Channel(
        id=request.app.state.deps.ids.create(),
        name=name,
        private=bool(private),
        created_at=_now(),
    )
    owner = domain.ChannelMember(
        channel_id=channel.id, user_id=caller.id, role=domain.Role.OWNER, joined_at=_now()
    )
    store.insert_channel_with_owner(channel, owner)
    return json_response(201, _channel_dto(channel))


@router.get("/channels")
async def list_channels(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
):
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    if before and not store.channel_exists(before):
        raise paging.unknown_before()
    rows = store.visible_channels(caller.id, before, limit + 1)
    dtos = [_channel_dto(c, store.member_count(c.id)) for c in rows]
    page = paging.build(dtos, limit, lambda d: d["id"])
    return json_response(200, page)


@router.get("/channels/{channel_id}")
async def get_channel(
    channel_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelReadGate = Depends(provide_channel_read_gate),
    store: Store = Depends(provide_store),
):
    channel = gate.authorize_read(channel_id, caller.id, False)
    return json_response(200, _channel_dto(channel, store.member_count(channel.id)))


@router.post("/channels/{channel_id}/join")
async def join_channel(
    channel_id: str,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
    writer: seams.MembershipWriter = Depends(provide_membership_writer),
):
    channel = store.channel_by_id(channel_id)
    if channel is None:
        raise apierr.not_found_channel()
    if store.membership(channel_id, caller.id) is not None:
        raise apierr.conflict("channel:member:already", "You are already a member.")
    if channel.private:
        raise apierr.not_found_channel()
    member = domain.ChannelMember(
        channel_id=channel_id, user_id=caller.id, role=domain.Role.MEMBER, joined_at=_now()
    )
    writer.add(member)
    return json_response(201, _membership_dto(member))


@router.post("/channels/{channel_id}/members")
async def add_member(
    channel_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    writer: seams.MembershipWriter = Depends(provide_membership_writer),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.ADMIN)
    body = await read_json(request)
    target_id = body.get("userId")
    target = store.user_by_id(target_id) if isinstance(target_id, str) else None
    if target is None:
        raise apierr.not_found("user:not_found", "User not found.")
    if store.membership(channel_id, target_id) is not None:
        raise apierr.conflict("channel:member:already", "That user is already a member.")
    member = domain.ChannelMember(
        channel_id=channel_id, user_id=target_id, role=domain.Role.MEMBER, joined_at=_now()
    )
    writer.add(member)
    return json_response(201, _membership_dto(member))


@router.post("/channels/{channel_id}/members/{target_id}/promote")
async def promote_member(
    channel_id: str,
    target_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.OWNER)
    target = store.membership(channel_id, target_id)
    if target is None:
        raise apierr.not_found("channel:member:not_found", "Member not found.")
    if target.role.at_least(domain.Role.ADMIN):
        raise apierr.conflict("channel:member:already", "That member is already an admin or owner.")
    store.update_member_role(channel_id, target_id, domain.Role.ADMIN)
    promoted = domain.ChannelMember(
        channel_id=channel_id, user_id=target_id, role=domain.Role.ADMIN, joined_at=target.joined_at
    )
    return json_response(200, _membership_dto(promoted))


@router.delete("/channels/{channel_id}/members/{target_id}")
async def remove_member(
    channel_id: str,
    target_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    writer: seams.MembershipWriter = Depends(provide_membership_writer),
):
    if target_id == caller.id:
        return _leave_channel(channel_id, caller.id, store, writer)
    return _kick_member(channel_id, caller.id, target_id, gate, store, writer)


def _leave_channel(
    channel_id: str, caller_id: str, store: Store, writer: seams.MembershipWriter
) -> Response:
    channel = store.channel_by_id(channel_id)
    if channel is None:
        raise apierr.not_found_channel()
    member = store.membership(channel_id, caller_id)
    if member is None:
        if channel.private:
            raise apierr.not_found_channel()
        raise apierr.not_found("channel:member:not_found", "Member not found.")
    if member.role == domain.Role.OWNER:
        raise apierr.conflict(
            "channel:owner:cannot_leave", "The owner cannot leave their own channel."
        )
    writer.remove(channel_id, caller_id)
    return Response(status_code=204)


def _kick_member(
    channel_id: str,
    caller_id: str,
    target_id: str,
    gate: seams.ChannelRoleGate,
    store: Store,
    writer: seams.MembershipWriter,
) -> Response:
    caller_membership = gate.authorize_role(channel_id, caller_id, domain.Role.ADMIN)
    target = store.membership(channel_id, target_id)
    if target is None:
        raise apierr.not_found("channel:member:not_found", "Member not found.")
    kicking_privileged = target.role == domain.Role.OWNER or (
        target.role == domain.Role.ADMIN and caller_membership.role != domain.Role.OWNER
    )
    if kicking_privileged:
        raise apierr.forbidden(
            "channel:role:forbidden", "Your role does not permit removing this member."
        )
    writer.remove(channel_id, target_id)
    return Response(status_code=204)


@router.delete("/channels/{channel_id}")
async def delete_channel(
    channel_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    cache: seams.MembershipCache = Depends(provide_cache),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.OWNER)
    store.delete_channel(channel_id)
    cache.invalidate(channel_id)
    return Response(status_code=204)


@router.get("/channels/{channel_id}/messages")
async def get_channel_messages(
    channel_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelReadGate = Depends(provide_channel_read_gate),
    store: Store = Depends(provide_store),
):
    gate.authorize_read(channel_id, caller.id, True)
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    if before and not store.channel_message_exists(channel_id, before):
        raise paging.unknown_before()
    rows = store.channel_messages(channel_id, before, limit + 1)
    dtos = [_channel_message_dto(m, []) for m in rows]
    page = paging.build(dtos, limit, lambda d: d["id"])
    return json_response(200, page)


@router.post("/channels/{channel_id}/read")
async def mark_channel_read(
    channel_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelReadGate = Depends(provide_channel_read_gate),
    unread: seams.UnreadCounters = Depends(provide_unread),
):
    gate.authorize_read(channel_id, caller.id, True)
    unread.reset(caller.id, channel_id)
    return Response(status_code=204)


def _channel_message_dto(
    message: domain.ChannelMessage, attachment_ids: list[str]
) -> dict[str, Any]:
    return {
        "id": message.id,
        "channelId": message.channel_id,
        "senderId": message.sender_id,
        "text": message.text,
        "attachmentIds": attachment_ids,
        "linkPreviewTitle": message.link_preview_title,
        "createdAt": message.created_at,
    }


@router.post("/channels/{channel_id}/messages")
async def post_channel_message(
    channel_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    previewer: seams.LinkPreviewer = Depends(provide_link_previewer),
    publisher: seams.MessagePostedPublisher = Depends(provide_publisher),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.MEMBER)
    body = await read_json(request)
    text = body.get("text")
    attachment_ids = body.get("attachmentIds") or []
    if not isinstance(text, str) or not (1 <= len(text) <= 4000):
        raise apierr.invalid("message:text:invalid", "text must be 1–4000 chars.")
    if not isinstance(attachment_ids, list) or len(attachment_ids) > 10:
        raise apierr.invalid(
            "message:attachment:invalid", "A message can reference at most 10 attachments."
        )
    _validate_attachments(store, channel_id, caller.id, attachment_ids)

    # Unfurl runs (bounded, graceful) BEFORE the insert so the title persists
    # with the row; a slow/failing upstream degrades to a null title (G-HTTP).
    title: str | None = None
    url = domain.first_url(text)
    if url is not None:
        title = previewer.preview(url)

    message = domain.ChannelMessage(
        id=request.app.state.deps.ids.create(),
        channel_id=channel_id,
        sender_id=caller.id,
        text=text,
        link_preview_title=title,
        created_at=_now(),
    )

    # Pinned write ordering (02-api.md §3, no outbox): open tx → insert message →
    # publish AWAITING broker confirmation → commit. A publish failure rolls back
    # and surfaces 503; the message is never half-posted (G-KAFKA producer).
    with store.begin() as conn:
        store.insert_channel_message(conn, message)
        store.attach_message_to_attachments(conn, message.id, attachment_ids)
        event = domain.MessagePosted(
            message_id=message.id,
            channel_id=channel_id,
            sender_id=caller.id,
            preview=domain.preview(text),
            posted_at=message.created_at,
        )
        publisher.publish(event)  # raises 503 on broker-down → tx rolls back
    return json_response(201, _channel_message_dto(message, list(attachment_ids)))


def _validate_attachments(
    store: Store, channel_id: str, caller_id: str, ids: list[str]
) -> None:
    """Enforce S-AT-04: every referenced attachment must be uploaded by the
    caller to THIS channel. A bad reference is 422 before any write."""
    if not ids:
        return
    owned = store.attachments_owned_in_channel(channel_id, caller_id, ids)
    if len(owned) != len(ids):
        raise apierr.invalid(
            "message:attachment:invalid",
            "Attachments must be uploaded to this channel by you and not already referenced.",
        )
