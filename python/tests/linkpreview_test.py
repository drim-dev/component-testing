"""Link preview / outbound HTTP (S-LP) acceptance scenarios, including the
catching halves of the G-HTTP case (timeout degradation, circuit breaker).
"""

from __future__ import annotations

import time

from harness.fixture import Fixture
from tests.helpers import client_for, seed_channel, seed_user


def test_s_lp_01_unfurl_success(fixture: Fixture) -> None:
    owner = seed_user(fixture, "lpowner")
    channel_id = seed_channel(fixture, owner, "general", private=False)
    fixture.unfurl.program_ok("Example")

    response = client_for(fixture, owner.id).post(
        f"/channels/{channel_id}/messages", {"text": "see https://example.com now"}
    )
    response.expect(201)
    assert response.json()["linkPreviewTitle"] == "Example"
    assert fixture.unfurl.request_count == 1


def test_s_lp_02_timeout_degrades_to_null(fixture: Fixture) -> None:
    """[G-HTTP] delay > 800 ms timeout → post 201 within 1.5 s, null preview."""
    owner = seed_user(fixture, "lpowner")
    channel_id = seed_channel(fixture, owner, "general", private=False)
    fixture.unfurl.program_delay(2.0)

    start = time.monotonic()
    response = client_for(fixture, owner.id).post(
        f"/channels/{channel_id}/messages", {"text": "slow https://example.com"}
    )
    elapsed = time.monotonic() - start
    response.expect(201)
    assert response.json()["linkPreviewTitle"] is None
    assert elapsed < 1.5, f"post took {elapsed:.2f}s — the timeout did not bound it"


def test_s_lp_03_server_error_degrades_to_null(fixture: Fixture) -> None:
    """[G-HTTP] upstream 500 → post 201, null preview."""
    owner = seed_user(fixture, "lpowner")
    channel_id = seed_channel(fixture, owner, "general", private=False)
    fixture.unfurl.program_server_error()
    response = client_for(fixture, owner.id).post(
        f"/channels/{channel_id}/messages", {"text": "broken https://example.com"}
    )
    response.expect(201)
    assert response.json()["linkPreviewTitle"] is None


def test_s_lp_04_circuit_breaker_opens(fixture: Fixture) -> None:
    """[G-HTTP] 5 failing posts (breaker opens) → 6th post 201 null preview AND
    stub request count == 5 (no 6th call)."""
    owner = seed_user(fixture, "lpowner")
    channel_id = seed_channel(fixture, owner, "general", private=False)
    fixture.unfurl.program_server_error()

    poster = client_for(fixture, owner.id)
    for i in range(6):
        response = poster.post(
            f"/channels/{channel_id}/messages", {"text": f"post {i} https://example.com"}
        )
        response.expect(201)
        assert response.json()["linkPreviewTitle"] is None
    assert fixture.unfurl.request_count == 5  # the breaker opened; the 6th never called out


def test_s_lp_05_direct_preview_proxy(fixture: Fixture) -> None:
    owner = seed_user(fixture, "lpowner")
    caller = client_for(fixture, owner.id)
    fixture.unfurl.program_ok("Proxied")
    ok = caller.get("/links/preview?url=https://example.com")
    ok.expect(200)
    assert ok.json()["title"] == "Proxied"

    fixture.unfurl.program_server_error()
    caller.get("/links/preview?url=https://example.com").expect(502).expect_code(
        "unfurl:upstream_failed"
    )
    caller.get("/links/preview?url=").expect(422)
