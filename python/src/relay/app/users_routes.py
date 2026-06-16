"""Users endpoints. POST /users is the only endpoint callable without identity."""

from __future__ import annotations

import re
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Request

from relay import apierr, domain
from relay.app.deps import provide_store
from relay.app.identity import require_user
from relay.app.requests import read_json
from relay.app.responses import json_response
from relay.store import Store, is_unique_violation

router = APIRouter()

_HANDLE_PATTERN = re.compile(r"^[a-z0-9_]+$")


def _now() -> datetime:
    return datetime.now(UTC)


def _user_dto(user: domain.User) -> dict[str, Any]:
    return {
        "id": user.id,
        "handle": user.handle,
        "displayName": user.display_name,
        "createdAt": user.created_at,
    }


@router.post("/users")
async def create_user(request: Request, store: Store = Depends(provide_store)):
    body = await read_json(request)
    handle = body.get("handle")
    display_name = body.get("displayName")
    if (
        not isinstance(handle, str)
        or not (3 <= len(handle) <= 32)
        or not _HANDLE_PATTERN.match(handle)
    ):
        raise apierr.invalid("user:handle:invalid", "handle must be 3–32 chars of [a-z0-9_].")
    if not isinstance(display_name, str) or not (1 <= len(display_name) <= 64):
        raise apierr.invalid("user:display_name:invalid", "displayName must be 1–64 chars.")

    user = domain.User(
        id=request.app.state.deps.ids.create(),
        handle=handle,
        display_name=display_name,
        created_at=_now(),
    )
    try:
        store.insert_user(user)
    except Exception as err:
        if is_unique_violation(err):
            raise apierr.conflict("user:handle:taken", "That handle is taken.") from err
        raise
    return json_response(201, _user_dto(user))


@router.get("/users/{user_id}")
async def get_user(
    user_id: str,
    store: Store = Depends(provide_store),
    _caller: domain.User = Depends(require_user),
):
    user = store.user_by_id(user_id)
    if user is None:
        raise apierr.not_found("user:not_found", "User not found.")
    return json_response(200, _user_dto(user))
