"""The correct G-HTTP seam: a REAL HTTP client with an 800 ms timeout that
degrades gracefully (timeout / 5xx / network error → None title, never
escapes), behind a circuit breaker (5 consecutive failures → skip the call for
30 s). Breaker state lives in Redis (resettable by the harness FLUSHDB). The
naive variant has no timeout/guard.
"""

from __future__ import annotations

import time

import httpx
import redis

UNFURL_TIMEOUT = 0.8  # 800 ms
BREAKER_THRESHOLD = 5
BREAKER_WINDOW = 30  # seconds
_FAILURES_KEY = "unfurl:breaker:failures"
_OPEN_UNTIL_KEY = "unfurl:breaker:open_until"


class LinkPreviewer:
    def __init__(self, client: redis.Redis, base_url: str) -> None:
        self._redis = client
        self._base_url = base_url.rstrip("/")
        self._http = httpx.Client(timeout=UNFURL_TIMEOUT)

    def preview(self, url: str) -> str | None:
        if self._breaker_open():
            return None  # breaker open: skip the call, degrade to no preview
        try:
            title = self._fetch(url)
        except (httpx.HTTPError, _UpstreamError):
            self._record_failure()
            return None  # graceful degradation — never surface the upstream failure
        self._record_success()
        return title

    def _fetch(self, url: str) -> str:
        response = self._http.get(self._base_url + "/unfurl", params={"url": url})
        if not 200 <= response.status_code < 300:
            raise _UpstreamError(response.status_code)
        return response.json()["title"]

    def _breaker_open(self) -> bool:
        raw = self._redis.get(_OPEN_UNTIL_KEY)
        if raw is None:
            return False
        return int(raw) > int(time.time() * 1000)

    def _record_success(self) -> None:
        self._redis.delete(_FAILURES_KEY)

    def _record_failure(self) -> None:
        failures = self._redis.incr(_FAILURES_KEY)
        if failures >= BREAKER_THRESHOLD:
            open_until = int(time.time() * 1000) + BREAKER_WINDOW * 1000
            self._redis.set(_OPEN_UNTIL_KEY, open_until, ex=BREAKER_WINDOW)


class _UpstreamError(Exception):
    """A non-2xx unfurl response — a failure for breaker accounting."""

    def __init__(self, status: int) -> None:
        super().__init__(f"unfurl upstream returned {status}")
        self.status = status
