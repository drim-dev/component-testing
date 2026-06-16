package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.infra.NotificationQueues;
import dev.drim.relay.naive.NaiveNotificationRecorder;
import dev.drim.relay.seams.NotificationRecorder;
import dev.drim.relay.store.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * G-RABBIT naive red→green demonstration: wires {@link NaiveNotificationRecorder} (no duplicate
 * handling) into the notification worker and confirms S-NT-02's catch goes RED — a redelivered
 * duplicate crash-loops into the DLQ instead of being acked.
 *
 * <p>This context consumes an ISOLATED queue ({@code notify.dm.gallery-naive}) so its worker never
 * competes with the correct worker in the base context (which consumes {@code notify.dm}) — both
 * are alive in Spring's context cache during the full suite. The queue name is plain config, not a
 * product change.
 */
@Import(GRabbitNaiveDemoTest.NaiveConfig.class)
class GRabbitNaiveDemoTest extends AcceptanceTestBase {

  private static final String QUEUE = "notify.dm.gallery-naive";
  private static final String DLQ = NotificationQueues.deadLetterQueue(QUEUE);

  @DynamicPropertySource
  static void isolatedQueue(DynamicPropertyRegistry registry) {
    registry.add("relay.rabbit.notify-queue", () -> QUEUE);
  }

  @Test
  @DisplayName("G-RABBIT naive demo: catch S-NT-02 goes red against NaiveNotificationRecorder")
  void naiveRecorderDeadLettersDuplicate() throws Exception {
    String a = seedUser("grba");
    String b = seedUser("grbb");
    String conv = seedConversation(a, b);
    // A real DM enqueues to this context's isolated queue; the naive worker records it.
    seedDmMessage(a, conv, "dup");
    RABBIT.awaitSettled(QUEUE);

    String dmMessageId =
        client(b)
            .get("/notifications")
            .expectStatus(200)
            .json()
            .path("items")
            .get(0)
            .path("dmMessageId")
            .asText();

    // Republish the SAME job (the duplicate) to the isolated queue; declare it first so the harness
    // (which knows only the canonical queue) can publish here.
    try (Channel ch = RABBIT.connection().createChannel()) {
      NotificationQueues.declare(ch, QUEUE);
      ch.basicPublish(
          "",
          QUEUE,
          com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN
              .builder()
              .contentType("application/json")
              .build(),
          new dev.drim.relay.infra.NotificationJobCodec(
                  dev.drim.relay.infra.EventCodecs.canonicalMapper())
              .serialize(
                  new dev.drim.relay.domain.Events.NotificationJob(
                      dmMessageId, conv, a, b, "dup")));
    }

    // The naive recorder nacks the UNIQUE violation MAX_ATTEMPTS times → the worker dead-letters
    // it.
    RABBIT.awaitDepth(DLQ, 1);

    NaiveDemoSupport.expectCatchToFail(
        "G-RABBIT",
        // S-NT-02's catch assertion: the DLQ stays empty. Against the naive recorder it now holds
        // the
        // dead-lettered duplicate → red.
        () -> assertThat(RABBIT.readyCount(DLQ)).isZero());
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    NotificationRecorder naiveNotificationRecorder(
        NotificationRepository notifications, IdFactory ids) {
      return new NaiveNotificationRecorder(notifications, ids);
    }
  }
}
