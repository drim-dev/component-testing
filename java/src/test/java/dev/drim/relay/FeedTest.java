package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.harness.KafkaHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feed / unread / Kafka (S-FD) from spec/06-acceptance.md, 1:1. Carries G-KAFKA (S-FD-01/02/05) and
 * G-CACHE (S-FD-06): a paused broker turns a post into a pinned 503 with no message row, the
 * fan-out reaches every member but the sender, and a re-published event must not double the feed
 * entry or the unread counter (idempotent projection).
 */
class FeedTest extends AcceptanceTestBase {

  @Test
  @DisplayName("S-FD-01 [G-KAFKA]: broker stopped; post → 503 events:unavailable; no message row")
  void brokerDownBlocksPost() {
    String owner = seedUser("fd01");
    String ch = seedChannel(owner, "fd01", false);
    KAFKA.stopBroker();
    try {
      client(owner)
          .post("/channels/" + ch + "/messages", RelayClient.body("text", "lost"))
          .expectStatus(503)
          .expectCode("events:unavailable");
      assertThat(DATABASE.count("channel_messages", "channel_id = '" + ch + "'")).isZero();
    } finally {
      KAFKA.startBroker();
    }
  }

  @Test
  @DisplayName(
      "S-FD-02 [G-KAFKA]: A posts in channel of A,B,C → feed entries for B and C; none for A")
  void fanoutToOtherMembers() {
    String a = seedUser("fd02a");
    String b = seedUser("fd02b");
    String c = seedUser("fd02c");
    String ch = seedChannel(a, "fd02", false);
    seedMember(a, ch, b);
    seedMember(a, ch, c);

    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "broadcast"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);

    assertThat(
            DATABASE.count("feed_entries", "user_id = '" + b + "' AND channel_id = '" + ch + "'"))
        .isEqualTo(1);
    assertThat(
            DATABASE.count("feed_entries", "user_id = '" + c + "' AND channel_id = '" + ch + "'"))
        .isEqualTo(1);
    assertThat(DATABASE.count("feed_entries", "user_id = '" + a + "'")).isZero();
  }

  @Test
  @DisplayName("S-FD-03: post → /me/unread shows 1 for B; posting again → 2")
  void unreadIncrements() {
    String a = seedUser("fd03a");
    String b = seedUser("fd03b");
    String ch = seedChannel(a, "fd03", false);
    seedMember(a, ch, b);

    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "1"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);
    assertThat(unreadFor(b, ch)).isEqualTo(1);

    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "2"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);
    assertThat(unreadFor(b, ch)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "S-FD-04: B POST /channels/{id}/read → 204; that channel's unread 0; others untouched")
  void markReadResetsCounter() {
    String a = seedUser("fd04a");
    String b = seedUser("fd04b");
    String ch1 = seedChannel(a, "fd04c1", false);
    String ch2 = seedChannel(a, "fd04c2", false);
    seedMember(a, ch1, b);
    seedMember(a, ch2, b);

    client(a)
        .post("/channels/" + ch1 + "/messages", RelayClient.body("text", "x"))
        .expectStatus(201);
    client(a)
        .post("/channels/" + ch2 + "/messages", RelayClient.body("text", "y"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);

    client(b).post("/channels/" + ch1 + "/read", null).expectStatus(204);
    assertThat(unreadFor(b, ch1)).isZero();
    assertThat(unreadFor(b, ch2)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "S-FD-05 [G-KAFKA]: re-publish same message.posted → still one feed entry, unread still 1")
  void idempotentProjection() {
    String a = seedUser("fd05a");
    String b = seedUser("fd05b");
    String ch = seedChannel(a, "fd05", false);
    seedMember(a, ch, b);

    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "once"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);

    String messageId =
        client(a)
            .get("/channels/" + ch + "/messages")
            .expectStatus(200)
            .json()
            .path("items")
            .get(0)
            .path("id")
            .asText();

    KAFKA.publish(
        new dev.drim.relay.domain.Events.MessagePosted(
            messageId, ch, a, "once", java.time.Instant.now()),
        KafkaHarness.TOPIC);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);

    assertThat(
            DATABASE.count("feed_entries", "user_id = '" + b + "' AND channel_id = '" + ch + "'"))
        .isEqualTo(1);
    assertThat(unreadFor(b, ch)).isEqualTo(1);
  }

  @Test
  @DisplayName("S-FD-06 [G-CACHE]: owner kicks B, then A posts → no new feed entry / unread for B")
  void kickedMemberGetsNoFanout() {
    String a = seedUser("fd06a");
    String b = seedUser("fd06b");
    String ch = seedChannel(a, "fd06", false);
    seedMember(a, ch, b);

    client(a).delete("/channels/" + ch + "/members/" + b).expectStatus(204);
    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "after kick"))
        .expectStatus(201);
    KAFKA.awaitConsumed(KafkaHarness.TOPIC, KafkaHarness.GROUP);

    assertThat(
            DATABASE.count("feed_entries", "user_id = '" + b + "' AND channel_id = '" + ch + "'"))
        .isZero();
    assertThat(unreadFor(b, ch)).isZero();
  }

  private long unreadFor(String userId, String channelId) {
    var channels = client(userId).get("/me/unread").expectStatus(200).json().path("channels");
    return channels.path(channelId).asLong(0);
  }
}
