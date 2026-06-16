"""Pagination pins (S-PG) — the [G-WEAKVAL] strict-bound scenarios. Canonical
endpoint: GET /channels/{id}/messages (member). The same rules bind ALL list
endpoints; the pins are asserted once here.
"""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import client_for, seed_channel, seed_user


def _owner_and_channel(fixture: Fixture) -> tuple[str, str]:
    owner = seed_user(fixture, "pgowner")
    channel_id = seed_channel(fixture, owner, "general", private=False)
    return owner.id, channel_id


def test_s_pg_01_limit_zero_is_422(fixture: Fixture) -> None:
    owner_id, channel_id = _owner_and_channel(fixture)
    client_for(fixture, owner_id).get(f"/channels/{channel_id}/messages?limit=0").expect(
        422
    ).expect_code("pagination:limit:out_of_range")


def test_s_pg_02_limit_over_max_is_422(fixture: Fixture) -> None:
    owner_id, channel_id = _owner_and_channel(fixture)
    client_for(fixture, owner_id).get(f"/channels/{channel_id}/messages?limit=101").expect(
        422
    ).expect_code("pagination:limit:out_of_range")


def test_s_pg_03_limit_not_a_number_is_422(fixture: Fixture) -> None:
    owner_id, channel_id = _owner_and_channel(fixture)
    client_for(fixture, owner_id).get(f"/channels/{channel_id}/messages?limit=abc").expect(
        422
    ).expect_code("pagination:limit:not_a_number")


def test_s_pg_04_unknown_before_is_422(fixture: Fixture) -> None:
    owner_id, channel_id = _owner_and_channel(fixture)
    client_for(fixture, owner_id).get(
        f"/channels/{channel_id}/messages?before=never-returned"
    ).expect(422).expect_code("pagination:before:unknown")


def test_s_pg_05_paginates_newest_first(fixture: Fixture) -> None:
    owner_id, channel_id = _owner_and_channel(fixture)
    owner = client_for(fixture, owner_id)
    for i in range(60):
        owner.post(f"/channels/{channel_id}/messages", {"text": f"m{i:02d}"}).expect(201)

    first = owner.get(f"/channels/{channel_id}/messages").json()
    assert len(first["items"]) == 50
    assert first["items"][0]["text"] == "m59"  # newest first
    assert first["nextBefore"] is not None

    rest = owner.get(
        f"/channels/{channel_id}/messages?before={first['nextBefore']}"
    ).json()
    assert len(rest["items"]) == 10
    assert rest["items"][0]["text"] == "m09"
    assert rest["nextBefore"] is None
