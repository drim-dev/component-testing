"""Feed / unread / Kafka (S-FD) acceptance scenarios, including the catching
halves of the G-KAFKA (producer broker-down, consumer idempotency) and G-CACHE
(no feed for a kicked member) cases.
"""

from __future__ import annotations

from datetime import UTC, datetime

from harness.fixture import Fixture
from harness.kafka_harness import TOPIC
from relay import domain
from relay.infra.codecs import serialize_message_posted
from tests.helpers import (
    await_until,
    client_for,
    db_count,
    seed_channel,
    seed_member,
    seed_user,
)


def _channel_with_members(fixture: Fixture):
    a = seed_user(fixture, "fda")
    b = seed_user(fixture, "fdb")
    c = seed_user(fixture, "fdc")
    channel_id = seed_channel(fixture, a, "general", private=False)
    seed_member(fixture, a, channel_id, b)
    seed_member(fixture, a, channel_id, c)
    return a, b, c, channel_id


def test_s_fd_01_kafka_broker_down_is_503(fixture: Fixture) -> None:
    """[G-KAFKA] harness stops the broker; member posts → 503; no message row.
    (Broker restarted afterwards.)"""
    a, _b, _c, channel_id = _channel_with_members(fixture)
    fixture.kafka.stop_broker()
    try:
        response = client_for(fixture, a.id).post(
            f"/channels/{channel_id}/messages", {"text": "lost?"}
        )
        response.expect(503).expect_code("events:unavailable")
        assert db_count(fixture, "channel_messages", "channel_id = :c", {"c": channel_id}) == 0
    finally:
        fixture.kafka.start_broker()


def test_s_fd_02_fanout_to_members_except_sender(fixture: Fixture) -> None:
    """[G-KAFKA await-shape] A posts → feed entries for B and C with preview; none for A."""
    a, b, c, channel_id = _channel_with_members(fixture)
    client_for(fixture, a.id).post(
        f"/channels/{channel_id}/messages", {"text": "broadcast"}
    ).expect(201)

    await_until(lambda: db_count(fixture, "feed_entries", "user_id = :u", {"u": b.id}) == 1)
    await_until(lambda: db_count(fixture, "feed_entries", "user_id = :u", {"u": c.id}) == 1)
    assert db_count(fixture, "feed_entries", "user_id = :u", {"u": a.id}) == 0
    feed_b = client_for(fixture, b.id).get("/feed").json()["items"]
    assert feed_b[0]["preview"] == "broadcast"


def test_s_fd_03_unread_increments(fixture: Fixture) -> None:
    a, b, _c, channel_id = _channel_with_members(fixture)
    poster = client_for(fixture, a.id)
    poster.post(f"/channels/{channel_id}/messages", {"text": "one"}).expect(201)
    await_until(
        lambda: client_for(fixture, b.id).get("/me/unread").json()["channels"].get(channel_id) == 1
    )
    poster.post(f"/channels/{channel_id}/messages", {"text": "two"}).expect(201)
    await_until(
        lambda: client_for(fixture, b.id).get("/me/unread").json()["channels"].get(channel_id) == 2
    )


def test_s_fd_04_read_resets_counter(fixture: Fixture) -> None:
    a, b, _c, channel_id = _channel_with_members(fixture)
    other_channel = seed_channel(fixture, a, "other", private=False)
    seed_member(fixture, a, other_channel, b)

    client_for(fixture, a.id).post(f"/channels/{channel_id}/messages", {"text": "x"}).expect(201)
    client_for(fixture, a.id).post(
        f"/channels/{other_channel}/messages", {"text": "y"}
    ).expect(201)

    def unread_of(cid: str) -> int:
        return client_for(fixture, b.id).get("/me/unread").json()["channels"].get(cid, 0)

    await_until(lambda: unread_of(channel_id) == 1)
    await_until(lambda: unread_of(other_channel) == 1)

    client_for(fixture, b.id).post(f"/channels/{channel_id}/read").expect(204)
    counts = client_for(fixture, b.id).get("/me/unread").json()["channels"]
    assert counts.get(channel_id, 0) == 0
    assert counts.get(other_channel) == 1  # other channel untouched


def test_s_fd_05_consumer_idempotent_on_redelivery(fixture: Fixture) -> None:
    """[G-KAFKA] harness re-publishes the same event → still exactly one feed
    entry for B AND the unread counter still 1 (feed and counter must not diverge)."""
    a, b, _c, channel_id = _channel_with_members(fixture)
    response = client_for(fixture, a.id).post(
        f"/channels/{channel_id}/messages", {"text": "dup"}
    )
    response.expect(201)
    message_id = response.json()["id"]
    await_until(
        lambda: db_count(
            fixture,
            "feed_entries",
            "user_id = :u AND message_id = :m",
            {"u": b.id, "m": message_id},
        )
        == 1
    )

    # Re-publish the SAME event directly to the suite's topic.
    event = domain.MessagePosted(
        message_id=message_id,
        channel_id=channel_id,
        sender_id=a.id,
        preview="dup",
        posted_at=datetime.now(UTC),
    )
    fixture.kafka.publish(TOPIC, channel_id, serialize_message_posted(event))
    fixture.kafka.await_consumed(TOPIC, "feed-fanout")

    assert db_count(
        fixture, "feed_entries", "user_id = :u AND message_id = :m", {"u": b.id, "m": message_id}
    ) == 1
    assert client_for(fixture, b.id).get("/me/unread").json()["channels"].get(channel_id) == 1


def test_s_fd_06_cache_no_feed_for_kicked_member(fixture: Fixture) -> None:
    """[G-CACHE] owner kicks B, then A posts → no new feed entry for B; B's unread
    counter for the channel not incremented."""
    a, b, _c, channel_id = _channel_with_members(fixture)
    client_for(fixture, a.id).delete(f"/channels/{channel_id}/members/{b.id}").expect(204)

    response = client_for(fixture, a.id).post(
        f"/channels/{channel_id}/messages", {"text": "after-kick"}
    )
    response.expect(201)
    message_id = response.json()["id"]
    fixture.kafka.await_consumed(TOPIC, "feed-fanout")

    assert db_count(
        fixture, "feed_entries", "user_id = :u AND message_id = :m", {"u": b.id, "m": message_id}
    ) == 0
    assert client_for(fixture, b.id).get("/me/unread").json()["channels"].get(channel_id, 0) == 0
