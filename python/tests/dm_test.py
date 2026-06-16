"""Direct messages (S-DM) acceptance scenarios, including the catching halves of
the G-IDOR, G-RACE, and G-TX gallery cases (the naive red->green demos live in
tests/gallery/naive_demos_test.py; the lying tests in tests/gallery/lying_test.py).
"""

from __future__ import annotations

import threading

from harness.fixture import Fixture
from tests.helpers import (
    client_for,
    db_count,
    seed_conversation,
    seed_user,
)


def test_s_dm_01_create_conversation(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    response = client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id})
    response.expect(201)
    body = response.json()
    assert body["participantIds"] == sorted([a.id, b.id])


def test_s_dm_02_create_is_idempotent(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    first = client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id})
    first.expect(201)
    conversation_id = first.json()["id"]
    # Repeat A->B then B->A → 200 both times, same id (idempotent, locked).
    again = client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id})
    again.expect(200)
    assert again.json()["id"] == conversation_id
    reverse = client_for(fixture, b.id).post("/dm/conversations", {"recipientId": a.id})
    reverse.expect(200)
    assert reverse.json()["id"] == conversation_id


def test_s_dm_03_create_with_self_is_422(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    client_for(fixture, a.id).post("/dm/conversations", {"recipientId": a.id}).expect(
        422
    ).expect_code("dm:recipient:self")


def test_s_dm_04_create_unknown_recipient_is_404(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    client_for(fixture, a.id).post("/dm/conversations", {"recipientId": "ghost"}).expect(
        404
    ).expect_code("user:not_found")


def test_s_dm_05_concurrent_create_yields_one_row(fixture: Fixture) -> None:
    """[G-RACE] >=8 concurrent creates for the same pair → exactly one row;
    all responses 200/201 with the same id; no 5xx."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    lo, hi = sorted([a.id, b.id])

    results: list[tuple[int, str | None]] = []
    barrier = threading.Barrier(8)

    def attempt() -> None:
        barrier.wait()
        response = client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id})
        body = response.json() if response.status in (200, 201) else {}
        results.append((response.status, body.get("id")))

    threads = [threading.Thread(target=attempt) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert all(status in (200, 201) for status, _ in results), results
    ids = {cid for _, cid in results if cid}
    assert len(ids) == 1, f"expected one conversation id, got {ids}"
    assert db_count(
        fixture, "dm_conversations", "user_lo = :lo AND user_hi = :hi", {"lo": lo, "hi": hi}
    ) == 1


def test_s_dm_06_partial_commit_leaves_no_rows(fixture: Fixture) -> None:
    """[G-TX] harness arms a one-shot trigger raising on the 2nd dm_participants
    insert; create → 500; DB-state: zero conversation AND zero participant rows."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    lo, hi = sorted([a.id, b.id])
    fixture.database.arm_participant_insert_fault(fixture.store.engine)

    client_for(fixture, a.id).post("/dm/conversations", {"recipientId": b.id}).expect(500)

    assert db_count(
        fixture, "dm_conversations", "user_lo = :lo AND user_hi = :hi", {"lo": lo, "hi": hi}
    ) == 0
    assert db_count(fixture, "dm_participants", "user_id = :uid", {"uid": a.id}) == 0


def test_s_dm_07_list_only_callers(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    c = seed_user(fixture, "dmc")
    seed_conversation(fixture, a, b)
    seed_conversation(fixture, b, c)  # not A's

    items = client_for(fixture, a.id).get("/dm/conversations").json()["items"]
    assert len(items) == 1


def test_s_dm_08_idor_conversation_read(fixture: Fixture) -> None:
    """[G-IDOR] C (non-participant) reads A-B's conversation → 404, byte-identical
    to an unknown-id 404."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    c = seed_user(fixture, "dmc")
    conversation_id = seed_conversation(fixture, a, b)

    forbidden = client_for(fixture, c.id).get(f"/dm/conversations/{conversation_id}")
    forbidden.expect(404).expect_code("dm:conversation:not_found")
    unknown = client_for(fixture, c.id).get("/dm/conversations/does-not-exist")
    unknown.expect(404)
    assert forbidden.raw == unknown.raw  # existence-hiding: byte-identical


def test_s_dm_09_idor_messages_read(fixture: Fixture) -> None:
    """[G-IDOR] C reads A-B's messages → 404; no message data leaks."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    c = seed_user(fixture, "dmc")
    conversation_id = seed_conversation(fixture, a, b)
    client_for(fixture, a.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "secret"}
    ).expect(201)

    response = client_for(fixture, c.id).get(f"/dm/conversations/{conversation_id}/messages")
    response.expect(404).expect_code("dm:conversation:not_found")
    assert b"secret" not in response.raw


def test_s_dm_10_idor_message_write(fixture: Fixture) -> None:
    """[G-IDOR] C posts into A-B's conversation → 404; DB-state: no row written."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    c = seed_user(fixture, "dmc")
    conversation_id = seed_conversation(fixture, a, b)

    client_for(fixture, c.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "intrusion"}
    ).expect(404).expect_code("dm:conversation:not_found")
    assert db_count(
        fixture, "dm_messages", "conversation_id = :cid", {"cid": conversation_id}
    ) == 0


def test_s_dm_11_list_messages_newest_first(fixture: Fixture) -> None:
    """The G-TAUT honest counterpart: list messages through real HTTP + real DB."""
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    conversation_id = seed_conversation(fixture, a, b)
    sender = client_for(fixture, a.id)
    for text_body in ("first", "second", "third"):
        sender.post(
            f"/dm/conversations/{conversation_id}/messages", {"text": text_body}
        ).expect(201)

    for viewer in (a, b):
        items = client_for(fixture, viewer.id).get(
            f"/dm/conversations/{conversation_id}/messages"
        ).json()["items"]
        assert [m["text"] for m in items] == ["third", "second", "first"]
        assert all(m["senderId"] == a.id for m in items)


def test_s_dm_12_message_text_validation(fixture: Fixture) -> None:
    a = seed_user(fixture, "dma")
    b = seed_user(fixture, "dmb")
    conversation_id = seed_conversation(fixture, a, b)
    sender = client_for(fixture, a.id)
    sender.post(f"/dm/conversations/{conversation_id}/messages", {"text": ""}).expect(
        422
    ).expect_code("message:text:invalid")
    sender.post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "x" * 4001}
    ).expect(422).expect_code("message:text:invalid")
