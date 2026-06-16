"""Request-body decoding: a JSON body or the pinned 422 request:body:invalid."""

from __future__ import annotations

import json
from typing import Any

from fastapi import Request

from relay import apierr


async def read_json(request: Request, *, allow_empty: bool = False) -> dict[str, Any]:
    raw = await request.body()
    if not raw:
        if allow_empty:
            return {}
        raise apierr.invalid("request:body:invalid", "Request body could not be read.")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        raise apierr.invalid("request:body:invalid", "Request body could not be read.") from None
    if not isinstance(parsed, dict):
        raise apierr.invalid("request:body:invalid", "Request body could not be read.")
    return parsed
