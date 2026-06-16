"""Response helpers: a JSON response that renders datetimes as ISO-8601 UTC with
a ``Z`` suffix (02-api.md §0) and the body shape the catalog asserts.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

from fastapi import Response


def _default(value: Any) -> Any:
    if isinstance(value, datetime):
        aware = value if value.tzinfo else value.replace(tzinfo=UTC)
        return aware.astimezone(UTC).isoformat().replace("+00:00", "Z")
    raise TypeError(f"not JSON-serializable: {type(value)!r}")


def json_response(status: int, body: Any) -> Response:
    payload = json.dumps(body, default=_default)
    return Response(content=payload, status_code=status, media_type="application/json")
