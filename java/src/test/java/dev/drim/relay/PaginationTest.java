package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pagination pins (S-PG) from spec/06-acceptance.md. The canonical endpoint is {@code GET
 * /channels/{id}/messages} as a member; the same rules bind every list endpoint, asserted once
 * here. S-PG-01..04 are [G-WEAKVAL] gallery pins (a weakened limit/cursor validator is the classic
 * gaming-the-test surface), so they are mandatory and never trimmed.
 */
class PaginationTest extends AcceptanceTestBase {

  private String memberChannel() {
    String owner = seedUser("pgowner");
    return seedChannel(owner, "pg-channel", false);
  }

  @Test
  @DisplayName("S-PG-01 [G-WEAKVAL]: limit=0 → 422 pagination:limit:out_of_range")
  void limitZero() {
    String owner = seedUser("pg01");
    String ch = seedChannel(owner, "pg01", false);
    client(owner)
        .get("/channels/" + ch + "/messages?limit=0")
        .expectStatus(422)
        .expectCode("pagination:limit:out_of_range");
  }

  @Test
  @DisplayName("S-PG-02 [G-WEAKVAL]: limit=101 → 422 pagination:limit:out_of_range")
  void limitTooLarge() {
    String owner = seedUser("pg02");
    String ch = seedChannel(owner, "pg02", false);
    client(owner)
        .get("/channels/" + ch + "/messages?limit=101")
        .expectStatus(422)
        .expectCode("pagination:limit:out_of_range");
  }

  @Test
  @DisplayName("S-PG-03 [G-WEAKVAL]: limit=abc → 422 pagination:limit:not_a_number")
  void limitNotANumber() {
    String owner = seedUser("pg03");
    String ch = seedChannel(owner, "pg03", false);
    client(owner)
        .get("/channels/" + ch + "/messages?limit=abc")
        .expectStatus(422)
        .expectCode("pagination:limit:not_a_number");
  }

  @Test
  @DisplayName("S-PG-04 [G-WEAKVAL]: before=<id never returned> → 422 pagination:before:unknown")
  void beforeUnknown() {
    String owner = seedUser("pg04");
    String ch = seedChannel(owner, "pg04", false);
    client(owner)
        .get("/channels/" + ch + "/messages?before=0000000000000")
        .expectStatus(422)
        .expectCode("pagination:before:unknown");
  }

  @Test
  @DisplayName(
      "S-PG-05: 60 messages → default 50 newest-first; before=nextBefore → remaining 10; null at end")
  void cursorWalk() {
    String owner = seedUser("pg05");
    String ch = seedChannel(owner, "pg05", false);
    for (int i = 0; i < 60; i++) {
      client(owner)
          .post("/channels/" + ch + "/messages", RelayClient.body("text", "m" + i))
          .expectStatus(201);
    }

    JsonNode page1 = client(owner).get("/channels/" + ch + "/messages").expectStatus(200).json();
    assertThat(page1.path("items")).hasSize(50);
    assertThat(page1.path("items").get(0).path("text").asText()).isEqualTo("m59");
    String nextBefore = page1.path("nextBefore").asText();
    assertThat(nextBefore).isNotBlank();

    JsonNode page2 =
        client(owner)
            .get("/channels/" + ch + "/messages?before=" + nextBefore)
            .expectStatus(200)
            .json();
    assertThat(page2.path("items")).hasSize(10);
    assertThat(page2.path("nextBefore").isNull()).isTrue();
  }
}
