package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.naive.NaiveFeedProjector;
import dev.drim.relay.seams.FeedProjector;
import dev.drim.relay.seams.UnreadCounters;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.FeedEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * G-KAFKA consumer naive red→green demonstration: wires {@link NaiveFeedProjector} (unconditional
 * increment, no idempotency) into the feed worker and confirms S-FD-05's catch goes RED — a
 * re-published event double-increments the unread counter while the feed entry stays at one row.
 *
 * <p>This context produces to and consumes an ISOLATED topic+group ({@code message-posted.gallery},
 * {@code feed-fanout-gallery}) so its worker never competes with the correct worker in the base
 * context — both are alive in Spring's context cache during the full suite. Topic/group are plain
 * config, not a product change.
 */
@Import(GKafkaConsumerNaiveDemoTest.NaiveConfig.class)
class GKafkaConsumerNaiveDemoTest extends AcceptanceTestBase {

  private static final String TOPIC = "message-posted.gallery";
  private static final String GROUP = "feed-fanout-gallery";

  @DynamicPropertySource
  static void isolatedFeed(DynamicPropertyRegistry registry) {
    registry.add("relay.kafka.feed-topic", () -> TOPIC);
    registry.add("relay.kafka.feed-group", () -> GROUP);
  }

  @Test
  @DisplayName("G-KAFKA consumer naive demo: catch S-FD-05 goes red against NaiveFeedProjector")
  void naiveProjectorDivergesOnReplay() {
    String a = seedUser("gkca");
    String b = seedUser("gkcb");
    String ch = seedChannel(a, "gkcons", false);
    seedMember(a, ch, b);

    // The post publishes to the isolated topic; this context's naive worker (isolated group)
    // consumes
    // it.
    client(a)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "once"))
        .expectStatus(201);
    KAFKA.awaitConsumed(TOPIC, GROUP);
    String messageId =
        client(a)
            .get("/channels/" + ch + "/messages")
            .expectStatus(200)
            .json()
            .path("items")
            .get(0)
            .path("id")
            .asText();

    NaiveDemoSupport.expectCatchToFail(
        "G-KAFKA-consumer",
        () -> {
          KAFKA.publish(
              new dev.drim.relay.domain.Events.MessagePosted(
                  messageId, ch, a, "once", java.time.Instant.now()),
              TOPIC);
          KAFKA.awaitConsumed(TOPIC, GROUP);
          assertThat(
                  DATABASE.count(
                      "feed_entries", "user_id = '" + b + "' AND channel_id = '" + ch + "'"))
              .isEqualTo(1);
          assertThat(unread(b, ch)).isEqualTo(1);
        });
  }

  private long unread(String userId, String channelId) {
    return client(userId)
        .get("/me/unread")
        .expectStatus(200)
        .json()
        .path("channels")
        .path(channelId)
        .asLong(0);
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    FeedProjector naiveFeedProjector(
        ChannelMemberRepository members,
        FeedEntryRepository feed,
        UnreadCounters unread,
        IdFactory ids) {
      return new NaiveFeedProjector(members, feed, unread, ids);
    }
  }
}
