package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct messages (S-DM) from spec/06-acceptance.md, 1:1. Carries four gallery catches: S-DM-05
 * [G-RACE] (concurrent create → one row), S-DM-06 [G-TX] (mid-transaction fault → full rollback),
 * and S-DM-08/09/10 [G-IDOR] (non-participant access hidden as a byte-identical 404).
 */
class DirectMessagesTest extends AcceptanceTestBase {

  @Test
  @DisplayName("S-DM-01: A creates conversation with B → 201, participantIds = sorted pair")
  void createConversation() {
    String a = seedUser("dma1");
    String b = seedUser("dmb1");
    JsonNode body =
        client(a)
            .post("/dm/conversations", RelayClient.body("recipientId", b))
            .expectStatus(201)
            .json();
    List<String> ids = new ArrayList<>();
    body.path("participantIds").forEach(n -> ids.add(n.asText()));
    List<String> sorted = ids.stream().sorted().toList();
    assertThat(ids).isEqualTo(sorted).containsExactlyInAnyOrder(a, b);
  }

  @Test
  @DisplayName("S-DM-02: repeat create (A→B then B→A) → 200 both, same id (idempotent, locked)")
  void idempotentCreate() {
    String a = seedUser("dma2");
    String b = seedUser("dmb2");
    String firstId = seedConversation(a, b);

    client(a).post("/dm/conversations", RelayClient.body("recipientId", b)).expectStatus(200);
    JsonNode back =
        client(b)
            .post("/dm/conversations", RelayClient.body("recipientId", a))
            .expectStatus(200)
            .json();
    assertThat(back.path("id").asText()).isEqualTo(firstId);
  }

  @Test
  @DisplayName("S-DM-03: create with self → 422 dm:recipient:self")
  void createWithSelf() {
    String a = seedUser("dma3");
    client(a)
        .post("/dm/conversations", RelayClient.body("recipientId", a))
        .expectStatus(422)
        .expectCode("dm:recipient:self");
  }

  @Test
  @DisplayName("S-DM-04: create with unknown recipient → 404 user:not_found")
  void createWithUnknown() {
    String a = seedUser("dma4");
    client(a)
        .post("/dm/conversations", RelayClient.body("recipientId", "0000000000000"))
        .expectStatus(404)
        .expectCode("user:not_found");
  }

  @Test
  @DisplayName(
      "S-DM-05 [G-RACE]: ≥8 concurrent creates for same pair → exactly one row, all 200/201")
  void concurrentCreate() throws Exception {
    String a = seedUser("dma5");
    String b = seedUser("dmb5");

    int n = 8;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        tasks.add(
            () -> client(a).post("/dm/conversations", RelayClient.body("recipientId", b)).status());
      }
      List<Future<Integer>> results = pool.invokeAll(tasks);
      for (Future<Integer> r : results) {
        int status = r.get();
        assertThat(status).as("no 5xx under the race").isIn(200, 201);
      }
    } finally {
      pool.shutdownNow();
    }

    String[] pair = {a, b};
    java.util.Arrays.sort(pair);
    assertThat(DATABASE.count("dm_conversations", "user_lo = '" + pair[0] + "'")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "S-DM-06 [G-TX]: fault on 2nd participant insert → 500; zero conversation+participant rows")
  void transactionRollback() {
    String a = seedUser("dma6");
    String b = seedUser("dmb6");
    DATABASE.armParticipantInsertFault();

    int status = client(a).post("/dm/conversations", RelayClient.body("recipientId", b)).status();
    assertThat(status).isEqualTo(500);

    String[] pair = {a, b};
    java.util.Arrays.sort(pair);
    assertThat(DATABASE.count("dm_conversations", "user_lo = '" + pair[0] + "'")).isZero();
    assertThat(DATABASE.count("dm_participants", "user_id = '" + a + "' OR user_id = '" + b + "'"))
        .isZero();
  }

  @Test
  @DisplayName("S-DM-07: GET /dm/conversations returns only the caller's, paginated")
  void listOwnConversations() {
    String a = seedUser("dma7");
    String b = seedUser("dmb7");
    String c = seedUser("dmc7");
    seedConversation(a, b);
    seedConversation(b, c);

    JsonNode list = client(a).get("/dm/conversations").expectStatus(200).json();
    assertThat(list.path("items")).hasSize(1);
  }

  @Test
  @DisplayName("S-DM-08 [G-IDOR]: C GET conversation → 404, body byte-identical to unknown-id 404")
  void nonParticipantGetConversation() {
    String a = seedUser("dma8");
    String b = seedUser("dmb8");
    String c = seedUser("dmc8");
    String convId = seedConversation(a, b);

    String hidden = client(c).get("/dm/conversations/" + convId).expectStatus(404).bodyString();
    String unknown =
        client(c).get("/dm/conversations/0000000000000").expectStatus(404).bodyString();
    assertThat(hidden).isEqualTo(unknown);
  }

  @Test
  @DisplayName("S-DM-09 [G-IDOR]: C GET conversation messages → 404; no message data leaks")
  void nonParticipantListMessages() {
    String a = seedUser("dma9");
    String b = seedUser("dmb9");
    String c = seedUser("dmc9");
    String convId = seedConversation(a, b);
    seedDmMessage(a, convId, "secret");

    String hidden =
        client(c).get("/dm/conversations/" + convId + "/messages").expectStatus(404).bodyString();
    assertThat(hidden).doesNotContain("secret");
    String unknown =
        client(c).get("/dm/conversations/0000000000000/messages").expectStatus(404).bodyString();
    assertThat(hidden).isEqualTo(unknown);
  }

  @Test
  @DisplayName("S-DM-10 [G-IDOR]: C POST conversation message → 404; DB-state: no row written")
  void nonParticipantPostMessage() {
    String a = seedUser("dma10");
    String b = seedUser("dmb10");
    String c = seedUser("dmc10");
    String convId = seedConversation(a, b);

    client(c)
        .post("/dm/conversations/" + convId + "/messages", RelayClient.body("text", "intrusion"))
        .expectStatus(404);
    assertThat(DATABASE.count("dm_messages", "sender_id = '" + c + "'")).isZero();
  }

  @Test
  @DisplayName("S-DM-11: A sends 3 messages → 201 each; both A and B list them newest-first")
  void sendAndListMessages() {
    String a = seedUser("dma11");
    String b = seedUser("dmb11");
    String convId = seedConversation(a, b);
    for (String t : new String[] {"one", "two", "three"}) {
      seedDmMessage(a, convId, t);
    }

    for (String viewer : new String[] {a, b}) {
      JsonNode list =
          client(viewer).get("/dm/conversations/" + convId + "/messages").expectStatus(200).json();
      assertThat(list.path("items")).hasSize(3);
      assertThat(list.path("items").get(0).path("text").asText()).isEqualTo("three");
      assertThat(list.path("items").get(0).path("senderId").asText()).isEqualTo(a);
    }
  }

  @Test
  @DisplayName("S-DM-12: message text empty / 4001 chars → 422 message:text:invalid")
  void messageTextInvalid() {
    String a = seedUser("dma12");
    String b = seedUser("dmb12");
    String convId = seedConversation(a, b);

    client(a)
        .post("/dm/conversations/" + convId + "/messages", RelayClient.body("text", ""))
        .expectStatus(422)
        .expectCode("message:text:invalid");
    client(a)
        .post(
            "/dm/conversations/" + convId + "/messages", RelayClient.body("text", "x".repeat(4001)))
        .expectStatus(422)
        .expectCode("message:text:invalid");
  }
}
