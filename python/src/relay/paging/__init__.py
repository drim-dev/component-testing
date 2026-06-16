"""The strict pagination contract (02-api.md §0.1): limit 1–100 (default 50),
VALIDATED never clamped. These exact 422s are the deterministic pin the §3
weakened-validation gaming story violates, so the bounds are identical across
all five languages.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import TypeVar

from relay import apierr

DEFAULT_LIMIT = 50
MIN_LIMIT = 1
MAX_LIMIT = 100

T = TypeVar("T")


def parse_limit(raw: str | None) -> int:
    """Validate the raw limit query param. Empty → default 50. Non-integer →
    422 pagination:limit:not_a_number. Out of [1,100] →
    422 pagination:limit:out_of_range. No silent clamping."""
    if raw is None or raw == "":
        return DEFAULT_LIMIT
    # Go's strconv.Atoi rejects floats, whitespace, and non-digits alike; int()
    # over a non-whitespace token matches that (it rejects "12.5", "abc", " 5 ").
    if raw != raw.strip():
        raise apierr.invalid("pagination:limit:not_a_number", "limit must be an integer.")
    try:
        limit = int(raw)
    except ValueError:
        raise apierr.invalid("pagination:limit:not_a_number", "limit must be an integer.") from None
    if limit < MIN_LIMIT or limit > MAX_LIMIT:
        raise apierr.invalid("pagination:limit:out_of_range", "limit must be between 1 and 100.")
    return limit


def unknown_before() -> apierr.ApiError:
    """The 422 for a before cursor that was never returned in this scope."""
    return apierr.invalid("pagination:before:unknown", "The before cursor is unknown.")


def build(rows: list[T], limit: int, id_of: Callable[[T], str]) -> dict[str, object]:
    """Assemble a page from rows fetched with limit+1: if more than limit came
    back, the extra row signals a next page and its cursor is the last kept
    item's id."""
    if len(rows) > limit:
        kept = rows[:limit]
        return {"items": kept, "nextBefore": id_of(kept[-1])}
    return {"items": rows, "nextBefore": None}
