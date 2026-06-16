"""Notifications / RabbitMQ (S-NT) acceptance scenarios, including the catching
halves of the G-RABBIT case (idempotency under redelivery, poison → DLQ).
"""

from __future__ import annotations

from harness.fixture import Fixture
from harness.rabbitmq_harness import QUEUE
from relay import domain
from relay.infra import dead_letter_queue
from tests.helpers import (
    await_until,
    client_for,
    db_count,
    seed_conversation,
    seed_user,
)


def test_s_nt_01_dm_creates_one_notification(fixture: Fixture) -> None:
    a = seed_user(fixture, "nta")
    b = seed_user(fixture, "ntb")
    conversation_id = seed_conversation(fixture, a, b)
    response = client_for(fixture, a.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "hello there"}
    )
    response.expect(201)
    dm_message_id = response.json()["id"]

    await_until(lambda: db_count(fixture, "notifications", "user_id = :u", {"u": b.id}) == 1)
    notification = client_for(fixture, b.id).get("/notifications").json()["items"][0]
    assert notification["type"] == "dm.message"
    assert notification["dmMessageId"] == dm_message_id
    assert notification["preview"] == "hello there"
    # None for the sender.
    assert db_count(fixture, "notifications", "user_id = :u", {"u": a.id}) == 0


def test_s_nt_02_redelivery_is_idempotent(fixture: Fixture) -> None:
    """[G-RABBIT] harness forces redelivery of the same job → exactly ONE row AND
    the DLQ stays empty (duplicate treated as success, not dead-lettered)."""
    a = seed_user(fixture, "nta")
    b = seed_user(fixture, "ntb")
    conversation_id = seed_conversation(fixture, a, b)
    response = client_for(fixture, a.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "once"}
    )
    response.expect(201)
    dm_message_id = response.json()["id"]
    await_until(
        lambda: db_count(fixture, "notifications", "dm_message_id = :m", {"m": dm_message_id}) == 1
    )

    # Force redelivery: publish the SAME job again to the main queue.
    job = domain.NotificationJob(
        dm_message_id=dm_message_id,
        conversation_id=conversation_id,
        sender_id=a.id,
        recipient_id=b.id,
        preview="once",
    )
    fixture.rabbit.publish(job, QUEUE)
    fixture.rabbit.await_settled(QUEUE)

    assert db_count(fixture, "notifications", "dm_message_id = :m", {"m": dm_message_id}) == 1
    assert fixture.rabbit.ready_count(dead_letter_queue(QUEUE)) == 0


def test_s_nt_03_poison_job_dead_letters(fixture: Fixture) -> None:
    """[G-RABBIT] poison job (unresolvable recipient) → after 3 attempts lands in
    the DLQ; zero notification rows."""
    poison = domain.NotificationJob(
        dm_message_id="ghost-message",
        conversation_id="ghost-conversation",
        sender_id="ghost-sender",
        recipient_id="ghost-recipient",
        preview="poison",
    )
    fixture.rabbit.publish(poison, QUEUE)

    fixture.rabbit.await_depth(dead_letter_queue(QUEUE), 1)
    fixture.rabbit.await_settled(QUEUE)
    assert db_count(fixture, "notifications", "dm_message_id = :m", {"m": "ghost-message"}) == 0


def test_s_nt_04_poison_then_valid_keeps_flowing(fixture: Fixture) -> None:
    """[G-RABBIT] publish poison then a valid job → the valid one is still
    processed; the main queue drains to empty."""
    a = seed_user(fixture, "nta")
    b = seed_user(fixture, "ntb")
    conversation_id = seed_conversation(fixture, a, b)

    poison = domain.NotificationJob(
        dm_message_id="ghost",
        conversation_id="ghost",
        sender_id="ghost",
        recipient_id="ghost",
        preview="poison",
    )
    fixture.rabbit.publish(poison, QUEUE)

    response = client_for(fixture, a.id).post(
        f"/dm/conversations/{conversation_id}/messages", {"text": "valid"}
    )
    response.expect(201)
    valid_id = response.json()["id"]

    await_until(
        lambda: db_count(fixture, "notifications", "dm_message_id = :m", {"m": valid_id}) == 1
    )
    fixture.rabbit.await_depth(dead_letter_queue(QUEUE), 1)
    fixture.rabbit.await_settled(QUEUE)


def test_s_nt_05_list_only_callers_newest_first(fixture: Fixture) -> None:
    a = seed_user(fixture, "nta")
    b = seed_user(fixture, "ntb")
    conversation_id = seed_conversation(fixture, a, b)
    sender = client_for(fixture, a.id)
    for text_body in ("n1", "n2"):
        sender.post(
            f"/dm/conversations/{conversation_id}/messages", {"text": text_body}
        ).expect(201)
    await_until(lambda: db_count(fixture, "notifications", "user_id = :u", {"u": b.id}) == 2)

    items = client_for(fixture, b.id).get("/notifications").json()["items"]
    assert [n["preview"] for n in items] == ["n2", "n1"]
    assert client_for(fixture, a.id).get("/notifications").json()["items"] == []
