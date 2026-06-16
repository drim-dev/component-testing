"""Direct-message endpoints (conversations + messages)."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Request

from relay import apierr, domain, paging, seams
from relay.app.deps import (
    provide_conversation_writer,
    provide_dm_access,
    provide_jobs,
    provide_store,
)
from relay.app.identity import require_user
from relay.app.requests import read_json
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()


def _now() -> datetime:
    return datetime.now(UTC)


def _conversation_dto(conversation: domain.Conversation) -> dict[str, Any]:
    return {
        "id": conversation.id,
        "participantIds": [conversation.user_lo, conversation.user_hi],
        "createdAt": conversation.created_at,
    }


def _dm_message_dto(message: domain.DmMessage) -> dict[str, Any]:
    return {
        "id": message.id,
        "conversationId": message.conversation_id,
        "senderId": message.sender_id,
        "text": message.text,
        "createdAt": message.created_at,
    }


@router.post("/dm/conversations")
async def create_conversation(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
    writer: seams.ConversationWriter = Depends(provide_conversation_writer),
):
    body = await read_json(request)
    recipient_id = body.get("recipientId")
    if recipient_id == caller.id:
        raise apierr.invalid("dm:recipient:self", "You cannot open a conversation with yourself.")
    recipient = store.user_by_id(recipient_id) if isinstance(recipient_id, str) else None
    if recipient is None:
        raise apierr.not_found("user:not_found", "User not found.")

    lo, hi = domain.normalize_pair(caller.id, recipient.id)
    result = writer.create(lo, hi)
    status = 201 if result.created else 200
    return json_response(status, _conversation_dto(result.conversation))


@router.get("/dm/conversations")
async def list_conversations(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
):
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    if before and not store.conversation_exists(before):
        raise paging.unknown_before()
    rows = store.conversations_for(caller.id, before, limit + 1)
    page = paging.build([_conversation_dto(c) for c in rows], limit, lambda d: d["id"])
    return json_response(200, page)


@router.get("/dm/conversations/{conversation_id}")
async def get_conversation(
    conversation_id: str,
    caller: domain.User = Depends(require_user),
    dm_access: seams.DmAccess = Depends(provide_dm_access),
):
    conversation = dm_access.get_for_participant(conversation_id, caller.id)
    if conversation is None:
        raise apierr.not_found_conversation()
    return json_response(200, _conversation_dto(conversation))


@router.post("/dm/conversations/{conversation_id}/messages")
async def create_dm_message(
    conversation_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    dm_access: seams.DmAccess = Depends(provide_dm_access),
    store: Store = Depends(provide_store),
    jobs: seams.NotificationJobs = Depends(provide_jobs),
):
    conversation = dm_access.get_for_participant(conversation_id, caller.id)
    if conversation is None:
        raise apierr.not_found_conversation()
    body = await read_json(request)
    text = body.get("text")
    if not isinstance(text, str) or not (1 <= len(text) <= 4000):
        raise apierr.invalid("message:text:invalid", "text must be 1–4000 chars.")

    message = domain.DmMessage(
        id=request.app.state.deps.ids.create(),
        conversation_id=conversation_id,
        sender_id=caller.id,
        text=text,
        created_at=_now(),
    )
    store.insert_dm_message(message)

    # Pinned ordering (02-api.md §2): the notification job is enqueued AFTER the
    # message commits, awaiting the broker's publisher confirmation. A publish
    # failure here is a 500 — the message stays.
    recipient = conversation.user_lo if conversation.user_lo != caller.id else conversation.user_hi
    job = domain.NotificationJob(
        dm_message_id=message.id,
        conversation_id=conversation_id,
        sender_id=caller.id,
        recipient_id=recipient,
        preview=domain.preview(text),
    )
    jobs.enqueue(job)
    return json_response(201, _dm_message_dto(message))


@router.get("/dm/conversations/{conversation_id}/messages")
async def get_dm_messages(
    conversation_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    dm_access: seams.DmAccess = Depends(provide_dm_access),
    store: Store = Depends(provide_store),
):
    conversation = dm_access.get_for_participant(conversation_id, caller.id)
    if conversation is None:
        raise apierr.not_found_conversation()
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    if before and not store.dm_message_exists(conversation_id, before):
        raise paging.unknown_before()
    rows = store.dm_messages(conversation_id, before, limit + 1)
    page = paging.build([_dm_message_dto(m) for m in rows], limit, lambda d: d["id"])
    return json_response(200, page)
