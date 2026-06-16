"""The trusted-header identity dependency.

Identity is the ``X-User-Id`` header (01-domain.md §3). Missing → 401
identity:missing; unknown → 401 identity:unknown. ``POST /users`` is the only
endpoint callable without it, so it simply does not depend on ``require_user``.
"""

from __future__ import annotations

from fastapi import Depends, Header, Request

from relay import apierr, domain
from relay.app.deps import provide_store
from relay.store import Store


def require_user(
    request: Request,
    store: Store = Depends(provide_store),
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
) -> domain.User:
    if not x_user_id:
        raise apierr.unauthorized("identity:missing", "X-User-Id header is required.")
    user = store.user_by_id(x_user_id)
    if user is None:
        raise apierr.unauthorized("identity:unknown", "X-User-Id does not match a known user.")
    return user
