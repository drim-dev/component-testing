package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.domain.Events;
import dev.drim.relay.harness.RabbitMqHarness;
import dev.drim.relay.infra.NotificationQueues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notifications / RabbitMQ (S-NT) from spec/06-acceptance.md, 1:1. Carries G-RABBIT
 * (S-NT-02/03/04): a redelivered duplicate is recorded exactly once and acked (NOT dead-lettered),
 * while a genuine poison job (unresolvable recipient FK) is retried then dead-lettered without
 * crash-looping and without blocking a following valid job.
 */
class NotificationsTest extends AcceptanceTestBase {

  private static final String DLQ = NotificationQueues.deadLetterQueue(RabbitMqHarness.QUEUE);

  @Test
  @DisplayName("S-NT-01: A sends DM to B → await: exactly one notification for B; none for A")
  void notificationFanout() {
    String a = seedUser("nt01a");
    String b = seedUser("nt01b");
    String conv = seedConversation(a, b);
    seedDmMessage(a, conv, "hello there");

    RABBIT.awaitSettled(RabbitMqHarness.QUEUE);
    assertThat(DATABASE.count("notifications", "user_id = '" + b + "'")).isEqualTo(1);
    assertThat(DATABASE.count("notifications", "user_id = '" + a + "'")).isZero();

    var list = client(b).get("/notifications").expectStatus(200).json();
    assertThat(list.path("items")).hasSize(1);
    assertThat(list.path("items").get(0).path("preview").asText()).isEqualTo("hello there");
  }

  @Test
  @DisplayName("S-NT-02 [G-RABBIT]: forced redelivery of same job → one row, DLQ stays empty")
  void duplicateIsIdempotent() {
    String a = seedUser("nt02a");
    String b = seedUser("nt02b");
    String conv = seedConversation(a, b);
    seedDmMessage(a, conv, "dup");
    RABBIT.awaitSettled(RabbitMqHarness.QUEUE);

    String dmMessageId =
        client(b)
            .get("/notifications")
            .expectStatus(200)
            .json()
            .path("items")
            .get(0)
            .path("dmMessageId")
            .asText();

    Events.NotificationJob duplicate = new Events.NotificationJob(dmMessageId, conv, a, b, "dup");
    RABBIT.publish(duplicate, RabbitMqHarness.QUEUE);
    RABBIT.awaitSettled(RabbitMqHarness.QUEUE);

    assertThat(DATABASE.count("notifications", "dm_message_id = '" + dmMessageId + "'"))
        .isEqualTo(1);
    assertThat(RABBIT.readyCount(DLQ)).isZero();
  }

  @Test
  @DisplayName(
      "S-NT-03 [G-RABBIT]: poison job → after 3 attempts lands in DLQ; zero notification rows")
  void poisonJobDeadLetters() {
    Events.NotificationJob poison =
        new Events.NotificationJob("dm-poison-3", "conv-x", "sender-x", "0000000000000", "x");
    RABBIT.publish(poison, RabbitMqHarness.QUEUE);

    RABBIT.awaitDepth(DLQ, 1);
    assertThat(DATABASE.count("notifications", "dm_message_id = 'dm-poison-3'")).isZero();
  }

  @Test
  @DisplayName("S-NT-04 [G-RABBIT]: poison then valid → valid still processed; main queue drains")
  void poisonDoesNotBlockValid() {
    String a = seedUser("nt04a");
    String b = seedUser("nt04b");
    String conv = seedConversation(a, b);

    // The poison job is synthetic (unresolvable recipient FK). The valid job must reference a real
    // dm_messages row (the notifications.dm_message_id FK), so it goes through the app — a
    // hand-built
    // job with a fake dm_message_id would itself be poison.
    Events.NotificationJob poison =
        new Events.NotificationJob("dm-poison-4", conv, a, "0000000000000", "x");
    RABBIT.publish(poison, RabbitMqHarness.QUEUE);

    seedDmMessage(a, conv, "valid");

    RABBIT.awaitSettled(RabbitMqHarness.QUEUE);
    assertThat(DATABASE.count("notifications", "user_id = '" + b + "'")).isEqualTo(1);
    assertThat(RABBIT.readyCount(RabbitMqHarness.QUEUE)).isZero();
  }

  @Test
  @DisplayName("S-NT-05: GET /notifications returns only the caller's, newest-first, paginated")
  void listOwnNotifications() {
    String a = seedUser("nt05a");
    String b = seedUser("nt05b");
    String conv = seedConversation(a, b);
    seedDmMessage(a, conv, "one");
    seedDmMessage(a, conv, "two");
    RABBIT.awaitSettled(RabbitMqHarness.QUEUE);

    var list = client(b).get("/notifications").expectStatus(200).json();
    assertThat(list.path("items")).hasSize(2);
    assertThat(list.path("items").get(0).path("preview").asText()).isEqualTo("two");
    assertThat(client(a).get("/notifications").expectStatus(200).json().path("items")).isEmpty();
  }
}
