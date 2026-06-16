"""Identity (S-ID) and Users (S-US) acceptance scenarios."""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import Client, client_for, seed_user


def test_s_id_01_missing_x_user_id_is_401(fixture: Fixture) -> None:
    client_for(fixture, None).get("/feed").expect(401).expect_code("identity:missing")


def test_s_id_02_unknown_x_user_id_is_401(fixture: Fixture) -> None:
    client_for(fixture, "nosuchuser").get("/feed").expect(401).expect_code("identity:unknown")


def test_s_us_01_create_user_valid(fixture: Fixture) -> None:
    response = client_for(fixture, None).post(
        "/users", {"handle": "alice", "displayName": "Alice"}
    )
    response.expect(201)
    body = response.json()
    assert body["handle"] == "alice"
    assert body["displayName"] == "Alice"
    assert body["id"]
    assert body["createdAt"]


def test_s_us_02_duplicate_handle_is_409(fixture: Fixture) -> None:
    seed_user(fixture, "bob")
    client_for(fixture, None).post(
        "/users", {"handle": "bob", "displayName": "Other Bob"}
    ).expect(409).expect_code("user:handle:taken")


def test_s_us_03_invalid_handle_is_422(fixture: Fixture) -> None:
    unauth: Client = client_for(fixture, None)
    for handle in ("ab", "UPPER", "has space"):
        unauth.post("/users", {"handle": handle, "displayName": "X"}).expect(
            422
        ).expect_code("user:handle:invalid")


def test_s_us_04_invalid_display_name_is_422(fixture: Fixture) -> None:
    unauth = client_for(fixture, None)
    unauth.post("/users", {"handle": "carol", "displayName": ""}).expect(422).expect_code(
        "user:display_name:invalid"
    )
    unauth.post("/users", {"handle": "carol2", "displayName": "x" * 65}).expect(
        422
    ).expect_code("user:display_name:invalid")


def test_s_us_05_get_existing_user(fixture: Fixture) -> None:
    user = seed_user(fixture, "dave")
    client_for(fixture, user.id).get(f"/users/{user.id}").expect(200)


def test_s_us_06_get_unknown_user_is_404(fixture: Fixture) -> None:
    user = seed_user(fixture, "erin")
    client_for(fixture, user.id).get("/users/nope").expect(404).expect_code("user:not_found")
