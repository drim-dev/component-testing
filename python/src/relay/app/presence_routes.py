"""Presence endpoints: heartbeat, user presence (unary), channel presence (stream)."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Response

from relay import apierr, domain, seams
from relay.app.deps import (
    provide_channel_read_gate,
    provide_heartbeats,
    provide_presence,
    provide_store,
)
from relay.app.identity import require_user
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()


def _status_of(online: bool) -> str:
    return "online" if online else "offline"


@router.post("/me/heartbeat")
async def heartbeat(
    caller: domain.User = Depends(require_user),
    heartbeats: seams.Heartbeats = Depends(provide_heartbeats),
):
    heartbeats.mark(caller.id)
    return Response(status_code=204)


@router.get("/users/{user_id}/presence")
async def get_user_presence(
    user_id: str,
    _caller: domain.User = Depends(require_user),
    store: Store = Depends(provide_store),
    presence: seams.PresenceClient = Depends(provide_presence),
):
    if store.user_by_id(user_id) is None:
        raise apierr.not_found("user:not_found", "User not found.")
    online = presence.user_presence(user_id)
    return json_response(200, {"userId": user_id, "status": _status_of(online)})


@router.get("/channels/{channel_id}/presence")
async def get_channel_presence(
    channel_id: str,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelReadGate = Depends(provide_channel_read_gate),
    store: Store = Depends(provide_store),
    presence: seams.PresenceClient = Depends(provide_presence),
):
    # Same visibility as message-read: member 200; public non-member 403; private/unknown 404.
    gate.authorize_read(channel_id, caller.id, True)
    member_ids = sorted(store.member_ids(channel_id))
    result = presence.channel_presence(member_ids)
    if result.incomplete:
        raise apierr.upstream(
            "presence:incomplete", "The presence stream terminated before completion."
        )
    members = [
        {"userId": status.user_id, "status": _status_of(status.online)}
        for status in result.statuses
    ]
    return json_response(200, {"members": members})
