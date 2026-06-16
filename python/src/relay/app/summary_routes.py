"""AI channel-summary endpoint. The Summarizer seam (G-LLM) owns prompt
assembly + output validation; this handler only gathers the sources.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from relay import apierr, domain, seams
from relay.app import DEFAULT_SUMMARY_MESSAGE_LIMIT
from relay.app.deps import provide_channel_role_gate, provide_store, provide_summarizer
from relay.app.identity import require_user
from relay.app.requests import read_json
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()


@router.post("/channels/{channel_id}/summary")
async def get_summary(
    channel_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    summarizer: seams.Summarizer = Depends(provide_summarizer),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.MEMBER)
    body = await read_json(request, allow_empty=True)
    limit = DEFAULT_SUMMARY_MESSAGE_LIMIT
    if "messageLimit" in body and body["messageLimit"] is not None:
        limit = body["messageLimit"]
        if not isinstance(limit, int) or isinstance(limit, bool) or not (1 <= limit <= 200):
            raise apierr.invalid(
                "summary:message_limit:out_of_range", "messageLimit must be 1–200."
            )

    messages = store.channel_messages(channel_id, "", limit)
    if not messages:
        raise apierr.invalid("summary:no_messages", "There is nothing to summarize.")

    # Resolve sender handles, oldest-first (stable, deterministic). The handler
    # only gathers the sources; assembly + output validation live behind the
    # Summarizer seam (G-LLM).
    sources: list[seams.SummarySource] = []
    for message in reversed(messages):
        user = store.user_by_id(message.sender_id)
        handle = user.handle if user is not None else message.sender_id
        sources.append(seams.SummarySource(handle=handle, text=message.text))

    summary = summarizer.summarize(sources)
    return json_response(200, {"summary": summary})
