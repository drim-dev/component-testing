"""The synchronous unfurl proxy — the only outbound-HTTP critical path
(02-api.md §6). Unlike the post-time unfurl, an upstream failure here surfaces
as 502 (the caller asked for the title directly), not graceful degradation.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from relay import apierr, domain, seams
from relay.app.deps import provide_link_previewer
from relay.app.identity import require_user
from relay.app.responses import json_response

router = APIRouter()


@router.get("/links/preview")
async def get_link_preview(
    request: Request,
    _caller: domain.User = Depends(require_user),
    previewer: seams.LinkPreviewer = Depends(provide_link_previewer),
):
    url = request.query_params.get("url") or ""
    if not url.strip() or domain.first_url(url) is None:
        raise apierr.invalid("unfurl:url:invalid", "A valid http(s) url is required.")
    title = previewer.preview(url)
    if title is None:
        raise apierr.upstream("unfurl:upstream_failed", "The unfurl upstream failed.")
    return json_response(200, {"title": title})
