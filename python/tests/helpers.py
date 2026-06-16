"""Test helpers: a Client wrapper over the FastAPI TestClient (the real HTTP
boundary as a given user), response assertions, seed helpers (write THROUGH the
real constraints so seeded states are reachable), and DB-state counts.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, TypeVar

from fastapi.testclient import TestClient
from sqlalchemy import text

from harness.fixture import Fixture

_T = TypeVar("_T")


def await_until(
    predicate: Callable[[], _T], *, deadline: float = 10.0, interval: float = 0.1
) -> _T:
    """Poll predicate until it returns truthy or the deadline passes — the
    eventual-consistency assertion shape (never sleep blindly; poll interval
    <= 100 ms, deadline 10 s default, per 04-dependencies.md §9)."""
    stop = time.monotonic() + deadline
    last: _T = predicate()
    while not last:
        if time.monotonic() > stop:
            raise TimeoutError("await_until deadline exceeded")
        time.sleep(interval)
        last = predicate()
    return last


@dataclass
class SeededUser:
    id: str
    handle: str
    display_name: str


class Response:
    def __init__(self, status: int, body: bytes, headers: dict[str, str]) -> None:
        self.status = status
        self._body = body
        self.headers = headers

    def expect(self, status: int) -> Response:
        assert self.status == status, (
            f"expected status {status}, got {self.status} (body: {self._body!r})"
        )
        return self

    def expect_code(self, code: str) -> Response:
        actual = self.json().get("code")
        assert actual == code, f"expected error code {code!r}, got {actual!r}"
        return self

    def json(self) -> dict[str, Any]:
        import json

        return json.loads(self._body)

    @property
    def raw(self) -> bytes:
        return self._body

    @property
    def text(self) -> str:
        return self._body.decode()


class Client:
    """Drives the assembled app's real HTTP boundary as a given user."""

    def __init__(self, client: TestClient, user_id: str | None) -> None:
        self._client = client
        self._user_id = user_id

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = dict(extra or {})
        if self._user_id is not None:
            headers["X-User-Id"] = self._user_id
        return headers

    def get(self, path: str) -> Response:
        return self._wrap(self._client.get(path, headers=self._headers()))

    def post(self, path: str, body: Any | None = None) -> Response:
        return self._wrap(self._client.post(path, json=body, headers=self._headers()))

    def post_multipart(self, path: str, files: dict[str, Any]) -> Response:
        return self._wrap(self._client.post(path, files=files, headers=self._headers()))

    def delete(self, path: str) -> Response:
        return self._wrap(self._client.delete(path, headers=self._headers()))

    @staticmethod
    def _wrap(response) -> Response:
        return Response(response.status_code, response.content, dict(response.headers))


def client_for(fixture: Fixture, user_id: str | None) -> Client:
    assert fixture.client is not None
    return Client(fixture.client, user_id)


def client_at(test_client: TestClient, user_id: str | None) -> Client:
    """Drive an arbitrary TestClient (e.g. a naive-override app) as a user."""
    return Client(test_client, user_id)


# ---- seed helpers (write THROUGH the real constraints) ----


def seed_user(fixture: Fixture, handle: str) -> SeededUser:
    response = client_for(fixture, None).post(
        "/users", {"handle": handle, "displayName": handle}
    )
    response.expect(201)
    data = response.json()
    return SeededUser(id=data["id"], handle=data["handle"], display_name=data["displayName"])


def seed_channel(fixture: Fixture, owner: SeededUser, name: str, private: bool) -> str:
    response = client_for(fixture, owner.id).post("/channels", {"name": name, "private": private})
    response.expect(201)
    return response.json()["id"]


def seed_member(fixture: Fixture, by: SeededUser, channel_id: str, target: SeededUser) -> None:
    client_for(fixture, by.id).post(
        f"/channels/{channel_id}/members", {"userId": target.id}
    ).expect(201)


def seed_conversation(fixture: Fixture, a: SeededUser, b: SeededUser) -> str:
    response = client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id})
    assert response.status in (200, 201), f"seed conversation: {response.status} ({response.text})"
    return response.json()["id"]


def seed_dm_message(
    fixture: Fixture, sender: SeededUser, conversation_id: str, text_body: str
) -> str:
    response = client_for(fixture, sender.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": text_body}
    )
    response.expect(201)
    return response.json()["id"]


def db_count(
    fixture: Fixture, table: str, where: str = "", params: dict[str, Any] | None = None
) -> int:
    """A direct DB-state count (the assertions that mocks cannot make)."""
    assert fixture.store is not None
    sql = f"SELECT COUNT(*) FROM {table}"
    if where:
        sql += f" WHERE {where}"
    with fixture.store.engine.connect() as conn:
        return int(conn.execute(text(sql), params or {}).scalar_one())
