"""Channels (S-CH) acceptance scenarios, including the catching halves of the
G-BOLA-READ, G-BOLA-ROLE, and G-CACHE gallery cases.
"""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import (
    client_for,
    db_count,
    seed_channel,
    seed_member,
    seed_user,
)


def test_s_ch_01_create_owner_membership(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    response = client_for(fixture, owner.id).post(
        "/channels", {"name": "general", "private": False}
    )
    response.expect(201)
    channel_id = response.json()["id"]
    assert db_count(
        fixture,
        "channel_members",
        "channel_id = :cid AND user_id = :uid AND role = 'owner'",
        {"cid": channel_id, "uid": owner.id},
    ) == 1


def test_s_ch_02_name_validation(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    creator = client_for(fixture, owner.id)
    creator.post("/channels", {"name": "", "private": False}).expect(422).expect_code(
        "channel:name:invalid"
    )
    creator.post("/channels", {"name": "x" * 101, "private": False}).expect(422).expect_code(
        "channel:name:invalid"
    )


def test_s_ch_03_list_visible_channels(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    other = seed_user(fixture, "chother")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    mine_private = seed_channel(fixture, owner, "minepriv", private=True)
    seed_channel(fixture, other, "theirpriv", private=True)  # not visible to owner

    items = client_for(fixture, owner.id).get("/channels").json()["items"]
    ids = {c["id"] for c in items}
    assert public_id in ids
    assert mine_private in ids
    assert len(ids) == 2


def test_s_ch_04_non_member_reads_public_metadata(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    body = client_for(fixture, outsider.id).get(f"/channels/{public_id}").json()
    assert body["memberCount"] == 1


def test_s_ch_05_bola_read_private_metadata_404(fixture: Fixture) -> None:
    """[G-BOLA-READ] non-member reads private channel metadata → 404, byte-
    identical to unknown-id 404."""
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    private_id = seed_channel(fixture, owner, "priv", private=True)

    forbidden = client_for(fixture, outsider.id).get(f"/channels/{private_id}")
    forbidden.expect(404).expect_code("channel:not_found")
    unknown = client_for(fixture, outsider.id).get("/channels/nope")
    unknown.expect(404)
    assert forbidden.raw == unknown.raw


def test_s_ch_06_join_public(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    joiner = seed_user(fixture, "chjoin")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    response = client_for(fixture, joiner.id).post(f"/channels/{public_id}/join")
    response.expect(201)
    assert response.json()["role"] == "member"


def test_s_ch_07_join_when_already_member_409(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    client_for(fixture, owner.id).post(f"/channels/{public_id}/join").expect(409).expect_code(
        "channel:member:already"
    )


def test_s_ch_08_join_private_non_member_404(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    client_for(fixture, outsider.id).post(f"/channels/{private_id}/join").expect(404)


def test_s_ch_09_owner_adds_to_private(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    target = seed_user(fixture, "chtarget")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members", {"userId": target.id}
    ).expect(201)


def test_s_ch_10_admin_adds_user(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    admin = seed_user(fixture, "chadmin")
    target = seed_user(fixture, "chtarget")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, admin)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members/{admin.id}/promote"
    ).expect(200)
    client_for(fixture, admin.id).post(
        f"/channels/{private_id}/members", {"userId": target.id}
    ).expect(201)


def test_s_ch_11_bola_role_member_adds_403(fixture: Fixture) -> None:
    """[G-BOLA-ROLE] plain member adds user → 403; DB-state: no membership written."""
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    target = seed_user(fixture, "chtarget")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, member)

    client_for(fixture, member.id).post(
        f"/channels/{private_id}/members", {"userId": target.id}
    ).expect(403).expect_code("channel:role:forbidden")
    assert db_count(
        fixture, "channel_members", "channel_id = :cid AND user_id = :uid",
        {"cid": private_id, "uid": target.id},
    ) == 0


def test_s_ch_12_add_existing_member_409(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, member)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members", {"userId": member.id}
    ).expect(409).expect_code("channel:member:already")


def test_s_ch_13_promote(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    admin = seed_user(fixture, "chadmin")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, member)
    seed_member(fixture, owner, private_id, admin)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members/{admin.id}/promote"
    ).expect(200)

    promote = client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members/{member.id}/promote"
    )
    promote.expect(200)
    assert promote.json()["role"] == "admin"
    # An admin attempting to promote → 403.
    client_for(fixture, admin.id).post(
        f"/channels/{private_id}/members/{member.id}/promote"
    ).expect(403).expect_code("channel:role:forbidden")


def test_s_ch_14_admin_kicks_member(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    admin = seed_user(fixture, "chadmin")
    member = seed_user(fixture, "chmember")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, admin)
    seed_member(fixture, owner, private_id, member)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members/{admin.id}/promote"
    ).expect(200)

    client_for(fixture, admin.id).delete(
        f"/channels/{private_id}/members/{member.id}"
    ).expect(204)
    assert db_count(
        fixture, "channel_members", "channel_id = :cid AND user_id = :uid",
        {"cid": private_id, "uid": member.id},
    ) == 0


def test_s_ch_15_bola_role_member_kicks_403(fixture: Fixture) -> None:
    """[G-BOLA-ROLE] member kicks member → 403; membership intact."""
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    victim = seed_user(fixture, "chvictim")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, member)
    seed_member(fixture, owner, private_id, victim)

    client_for(fixture, member.id).delete(
        f"/channels/{private_id}/members/{victim.id}"
    ).expect(403).expect_code("channel:role:forbidden")
    assert db_count(
        fixture, "channel_members", "channel_id = :cid AND user_id = :uid",
        {"cid": private_id, "uid": victim.id},
    ) == 1


def test_s_ch_16_cache_invalidation_on_kick(fixture: Fixture) -> None:
    """[G-CACHE] B reads (warms cache), owner kicks B, B reads again immediately
    → private 404 / Redis membership key invalidated."""
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, member)

    # B reads messages → warms the membership cache (asserted via Redis).
    client_for(fixture, member.id).get(f"/channels/{private_id}/messages").expect(200)
    assert fixture.redis.members_cache_present(private_id)
    assert member.id in fixture.redis.cached_members(private_id)

    client_for(fixture, owner.id).delete(
        f"/channels/{private_id}/members/{member.id}"
    ).expect(204)

    # Immediately denied; cache key invalidated (absent, or rewritten without B).
    client_for(fixture, member.id).get(f"/channels/{private_id}/messages").expect(404)
    assert member.id not in fixture.redis.cached_members(private_id)


def test_s_ch_17_leave(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    client_for(fixture, member.id).post(f"/channels/{public_id}/join").expect(201)
    client_for(fixture, member.id).delete(
        f"/channels/{public_id}/members/{member.id}"
    ).expect(204)
    # Owner cannot leave.
    client_for(fixture, owner.id).delete(
        f"/channels/{public_id}/members/{owner.id}"
    ).expect(409).expect_code("channel:owner:cannot_leave")


def test_s_ch_18_owner_kicks_admin_admin_cannot(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    admin1 = seed_user(fixture, "chadmin1")
    admin2 = seed_user(fixture, "chadmin2")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    for admin in (admin1, admin2):
        seed_member(fixture, owner, private_id, admin)
        client_for(fixture, owner.id).post(
            f"/channels/{private_id}/members/{admin.id}/promote"
        ).expect(200)

    # admin kicks admin → 403.
    client_for(fixture, admin1.id).delete(
        f"/channels/{private_id}/members/{admin2.id}"
    ).expect(403)
    # owner kicks admin → 204.
    client_for(fixture, owner.id).delete(
        f"/channels/{private_id}/members/{admin1.id}"
    ).expect(204)


def test_s_ch_19_bola_role_delete_channel_403(fixture: Fixture) -> None:
    """[G-BOLA-ROLE] admin deletes channel → 403; member deletes → 403; intact."""
    owner = seed_user(fixture, "chowner")
    admin = seed_user(fixture, "chadmin")
    member = seed_user(fixture, "chmember")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    seed_member(fixture, owner, private_id, admin)
    seed_member(fixture, owner, private_id, member)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/members/{admin.id}/promote"
    ).expect(200)

    client_for(fixture, admin.id).delete(f"/channels/{private_id}").expect(403).expect_code(
        "channel:role:forbidden"
    )
    client_for(fixture, member.id).delete(f"/channels/{private_id}").expect(403)
    assert db_count(fixture, "channels", "id = :id", {"id": private_id}) == 1


def test_s_ch_20_owner_deletes_channel(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    member = seed_user(fixture, "chmember")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    client_for(fixture, member.id).post(f"/channels/{public_id}/join").expect(201)
    client_for(fixture, owner.id).post(
        f"/channels/{public_id}/messages", {"text": "hi"}
    ).expect(201)

    client_for(fixture, owner.id).delete(f"/channels/{public_id}").expect(204)
    assert db_count(fixture, "channels", "id = :id", {"id": public_id}) == 0
    assert db_count(fixture, "channel_members", "channel_id = :cid", {"cid": public_id}) == 0
    assert db_count(fixture, "channel_messages", "channel_id = :cid", {"cid": public_id}) == 0
    client_for(fixture, owner.id).get(f"/channels/{public_id}").expect(404)


def test_s_ch_21_bola_read_private_messages_404(fixture: Fixture) -> None:
    """[G-BOLA-READ] non-member reads private channel messages → 404; no items leak."""
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    client_for(fixture, owner.id).post(
        f"/channels/{private_id}/messages", {"text": "classified"}
    ).expect(201)

    response = client_for(fixture, outsider.id).get(f"/channels/{private_id}/messages")
    response.expect(404).expect_code("channel:not_found")
    assert b"classified" not in response.raw


def test_s_ch_22_non_member_public_messages_403(fixture: Fixture) -> None:
    """Locked: public = metadata only. Non-member reads public messages → 403."""
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    client_for(fixture, outsider.id).get(f"/channels/{public_id}/messages").expect(
        403
    ).expect_code("channel:membership_required")


def test_s_ch_23_post_message_authorization(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    outsider = seed_user(fixture, "chout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    private_id = seed_channel(fixture, owner, "priv", private=True)

    client_for(fixture, owner.id).post(
        f"/channels/{public_id}/messages", {"text": "hi"}
    ).expect(201)
    client_for(fixture, outsider.id).post(
        f"/channels/{public_id}/messages", {"text": "x"}
    ).expect(403)
    client_for(fixture, outsider.id).post(
        f"/channels/{private_id}/messages", {"text": "x"}
    ).expect(404)
    assert db_count(
        fixture, "channel_messages", "sender_id = :uid", {"uid": outsider.id}
    ) == 0


def test_s_ch_24_post_validation(fixture: Fixture) -> None:
    owner = seed_user(fixture, "chowner")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    poster = client_for(fixture, owner.id)
    poster.post(f"/channels/{public_id}/messages", {"text": ""}).expect(422).expect_code(
        "message:text:invalid"
    )
    poster.post(f"/channels/{public_id}/messages", {"text": "x" * 4001}).expect(
        422
    ).expect_code("message:text:invalid")
    poster.post(
        f"/channels/{public_id}/messages",
        {"text": "ok", "attachmentIds": [f"a{i}" for i in range(11)]},
    ).expect(422).expect_code("message:attachment:invalid")
