"""Presence / gRPC (S-PR) acceptance scenarios, including the catching half of
the G-GRPC case (a partial stream must not be returned as complete).
"""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import client_for, seed_channel, seed_member, seed_user


def test_s_pr_01_unary_online_from_presence(fixture: Fixture) -> None:
    a = seed_user(fixture, "pra")
    b = seed_user(fixture, "prb")
    fixture.presence.set_online(b.id)
    response = client_for(fixture, a.id).get(f"/users/{b.id}/presence")
    response.expect(200)
    assert response.json()["status"] == "online"


def test_s_pr_02_unary_offline_without_heartbeat(fixture: Fixture) -> None:
    a = seed_user(fixture, "pra")
    c = seed_user(fixture, "prc")
    response = client_for(fixture, a.id).get(f"/users/{c.id}/presence")
    response.expect(200)
    assert response.json()["status"] == "offline"


def test_s_pr_03_channel_presence_complete_stream(fixture: Fixture) -> None:
    owner = seed_user(fixture, "prowner")
    members = [seed_user(fixture, f"prm{i}") for i in range(4)]
    channel_id = seed_channel(fixture, owner, "general", private=True)
    for member in members:
        seed_member(fixture, owner, channel_id, member)
    # 2 of the 5 online.
    fixture.presence.set_online(owner.id)
    fixture.presence.set_online(members[0].id)

    response = client_for(fixture, owner.id).get(f"/channels/{channel_id}/presence")
    response.expect(200)
    entries = response.json()["members"]
    assert len(entries) == 5  # stream consumed to completion
    online = {e["userId"] for e in entries if e["status"] == "online"}
    assert online == {owner.id, members[0].id}


def test_s_pr_04_grpc_partial_stream_is_502(fixture: Fixture) -> None:
    """[G-GRPC] harness arms "stream fails after 2 messages" → channel presence →
    502; response contains NO partial list."""
    owner = seed_user(fixture, "prowner")
    members = [seed_user(fixture, f"prm{i}") for i in range(4)]
    channel_id = seed_channel(fixture, owner, "general", private=True)
    for member in members:
        seed_member(fixture, owner, channel_id, member)
    fixture.presence.fail_stream_after(2)

    response = client_for(fixture, owner.id).get(f"/channels/{channel_id}/presence")
    response.expect(502).expect_code("presence:incomplete")
    assert "members" not in response.json()


def test_s_pr_05_non_member_presence(fixture: Fixture) -> None:
    owner = seed_user(fixture, "prowner")
    outsider = seed_user(fixture, "prout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    private_id = seed_channel(fixture, owner, "priv", private=True)
    client_for(fixture, outsider.id).get(f"/channels/{public_id}/presence").expect(403)
    client_for(fixture, outsider.id).get(f"/channels/{private_id}/presence").expect(404)
