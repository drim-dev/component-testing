"""Relay's error model.

Every deliberately-raised error carries an HTTP status and the pinned
``area:entity:reason`` code the acceptance catalog asserts. Handlers raise
``ApiError``; a FastAPI exception handler renders the pinned JSON body
(02-api.md §0).

Existence-hiding (01-domain.md §5) is a property of the *codes*, not of special
routing: an unknown-id 404 and an unauthorized 404 raise the SAME code+message,
so their JSON bodies are byte-identical — exactly what the G-IDOR / G-BOLA-READ
/ G-S3 catches assert.
"""

from __future__ import annotations


class ApiError(Exception):
    """A Relay API error: an HTTP status, a pinned code, and human text."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message

    def body(self) -> dict[str, object]:
        return {"status": self.status, "code": self.code, "message": self.message}


def unauthorized(code: str, message: str) -> ApiError:
    """401 — the only identity failures (missing / unknown X-User-Id)."""
    return ApiError(401, code, message)


def forbidden(code: str, message: str) -> ApiError:
    """403 — the caller can see the resource but lacks the membership/role."""
    return ApiError(403, code, message)


def not_found(code: str, message: str) -> ApiError:
    """404 — resource absent OR existence hidden from an unauthorized caller."""
    return ApiError(404, code, message)


def conflict(code: str, message: str) -> ApiError:
    """409 — state conflict (duplicate handle, already a member, owner cannot leave)."""
    return ApiError(409, code, message)


def too_large(code: str, message: str) -> ApiError:
    """413 — attachment over the size limit."""
    return ApiError(413, code, message)


def invalid(code: str, message: str) -> ApiError:
    """422 — input failed validation or a business rule (no silent clamping)."""
    return ApiError(422, code, message)


def upstream(code: str, message: str) -> ApiError:
    """502 — an upstream (model / unfurl / presence stream) violated its contract."""
    return ApiError(502, code, message)


def unavailable(code: str, message: str) -> ApiError:
    """503 — required infrastructure (the event broker) is unavailable."""
    return ApiError(503, code, message)


def not_found_conversation() -> ApiError:
    """The existence-hiding 404 for DMs — the SAME code+message for an unknown id
    and for a non-participant, so the JSON bodies are byte-identical (G-IDOR)."""
    return not_found("dm:conversation:not_found", "Conversation not found.")


def not_found_channel() -> ApiError:
    return not_found("channel:not_found", "Channel not found.")


def not_found_attachment() -> ApiError:
    return not_found("attachment:not_found", "Attachment not found.")
