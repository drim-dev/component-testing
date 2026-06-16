"""Attachment upload + download. Download authorization is the G-S3 seam."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, Request, Response
from starlette.datastructures import UploadFile

from relay import apierr, domain, seams
from relay.app.deps import (
    provide_attachment_access,
    provide_channel_role_gate,
    provide_store,
    provide_store3,
)
from relay.app.identity import require_user
from relay.app.responses import json_response
from relay.store import Store

router = APIRouter()

MAX_ATTACHMENT_BYTES = 1 << 20  # 1 MiB


def _now() -> datetime:
    return datetime.now(UTC)


def _attachment_dto(attachment: domain.Attachment) -> dict[str, Any]:
    return {
        "id": attachment.id,
        "channelId": attachment.channel_id,
        "filename": attachment.filename,
        "sizeBytes": attachment.size_bytes,
        "createdAt": attachment.created_at,
    }


@router.post("/channels/{channel_id}/attachments")
async def upload_attachment(
    channel_id: str,
    request: Request,
    caller: domain.User = Depends(require_user),
    gate: seams.ChannelRoleGate = Depends(provide_channel_role_gate),
    store: Store = Depends(provide_store),
    store3: seams.AttachmentStore = Depends(provide_store3),
):
    gate.authorize_role(channel_id, caller.id, domain.Role.MEMBER)
    form = await request.form()
    upload = form.get("file")
    if not isinstance(upload, UploadFile):
        raise apierr.invalid("attachment:invalid", "A file field is required.")
    content = await upload.read()
    if len(content) > MAX_ATTACHMENT_BYTES:
        raise apierr.too_large("attachment:too_large", "The attachment exceeds the 1 MiB limit.")
    if len(content) == 0:
        raise apierr.invalid("attachment:empty", "The attachment is empty.")

    attachment_id = request.app.state.deps.ids.create()
    storage_key = f"{channel_id}/{attachment_id}"
    store3.put(storage_key, content)
    attachment = domain.Attachment(
        id=attachment_id,
        channel_id=channel_id,
        uploader_id=caller.id,
        message_id=None,
        filename=upload.filename or "file",
        size_bytes=len(content),
        storage_key=storage_key,
        created_at=_now(),
    )
    store.insert_attachment(attachment)
    return json_response(201, _attachment_dto(attachment))


@router.get("/attachments/{attachment_id}")
async def download_attachment(
    attachment_id: str,
    caller: domain.User = Depends(require_user),
    access: seams.AttachmentAccess = Depends(provide_attachment_access),
    store3: seams.AttachmentStore = Depends(provide_store3),
):
    attachment = access.authorize(attachment_id, caller.id)
    content = store3.get(attachment.storage_key)
    return Response(
        content=content,
        status_code=200,
        media_type="application/octet-stream",
        headers={"Content-Disposition": f'attachment; filename="{attachment.filename}"'},
    )
