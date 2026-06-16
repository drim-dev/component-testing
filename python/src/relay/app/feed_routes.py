"""Notifications, feed, and unread-counter endpoints."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request

from relay import domain, paging, seams
from relay.app.deps import provide_store, provide_unread
from relay.app.identity import require_user
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()


@router.get("/notifications")
async def get_notifications(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
):
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    rows = store.notifications_for(caller.id, before, limit + 1)
    dtos: list[dict[str, Any]] = [
        {
            "id": n.id,
            "type": "dm.message",
            "dmMessageId": n.dm_message_id,
            "conversationId": n.conversation_id,
            "senderId": n.sender_id,
            "preview": n.preview,
            "createdAt": n.created_at,
        }
        for n in rows
    ]
    page = paging.build(dtos, limit, lambda d: d["id"])
    return json_response(200, page)


@router.get("/feed")
async def get_feed(
    request: Request,
    caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
):
    limit = paging.parse_limit(request.query_params.get("limit"))
    before = request.query_params.get("before") or ""
    rows = store.feed_for(caller.id, before, limit + 1)
    # The feed cursor (feed_entries.id) is not part of the DTO, so carry it alongside.
    if len(rows) > limit:
        kept = rows[:limit]
        page = {"items": [_feed_dto(f) for f in kept], "nextBefore": kept[-1].id}
    else:
        page = {"items": [_feed_dto(f) for f in rows], "nextBefore": None}
    return json_response(200, page)


def _feed_dto(entry: domain.FeedEntry) -> dict[str, Any]:
    return {
        "channelId": entry.channel_id,
        "messageId": entry.message_id,
        "senderId": entry.sender_id,
        "preview": entry.preview,
        "createdAt": entry.created_at,
    }


@router.get("/me/unread")
async def get_unread(
    caller: domain.User = Depends(require_user),
    unread: seams.UnreadCounters = Depends(provide_unread),
):
    return json_response(200, {"channels": unread.for_user(caller.id)})
