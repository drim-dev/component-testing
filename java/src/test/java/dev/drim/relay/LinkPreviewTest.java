package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Link preview / outbound HTTP (S-LP) from spec/06-acceptance.md, 1:1. Carries G-HTTP
 * (S-LP-02/03/04): a slow/failing upstream must NOT block or fail the post (graceful null preview),
 * and after 5 failures the circuit breaker opens so the 6th post issues NO upstream call.
 */
class LinkPreviewTest extends AcceptanceTestBase {

  @Test
  @DisplayName(
      "S-LP-01: 200 {title} → post with URL → 201 linkPreviewTitle:Example; one upstream call")
  void successfulPreview() {
    String owner = seedUser("lp01");
    String ch = seedChannel(owner, "lp01", false);
    UNFURL.programOk("Example");

    var body =
        client(owner)
            .post(
                "/channels/" + ch + "/messages",
                RelayClient.body("text", "see " + UNFURL.baseUrl() + "/unfurl"))
            .expectStatus(201)
            .json();
    assertThat(body.path("linkPreviewTitle").asText()).isEqualTo("Example");
    assertThat(UNFURL.requestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("S-LP-02 [G-HTTP]: upstream delay 2s (> 800ms) → post 201 within 1.5s, null preview")
  void slowUpstreamDoesNotBlock() {
    String owner = seedUser("lp02");
    String ch = seedChannel(owner, "lp02", false);
    UNFURL.programDelay(2000);

    long start = System.currentTimeMillis();
    var body =
        client(owner)
            .post(
                "/channels/" + ch + "/messages",
                RelayClient.body("text", "see " + UNFURL.baseUrl() + "/unfurl"))
            .expectStatus(201)
            .json();
    long elapsed = System.currentTimeMillis() - start;
    assertThat(elapsed).as("post must not wait for the slow upstream").isLessThan(1500);
    assertThat(body.path("linkPreviewTitle").isNull()).isTrue();
  }

  @Test
  @DisplayName("S-LP-03 [G-HTTP]: upstream 500 → post 201, null preview")
  void upstreamErrorIsGraceful() {
    String owner = seedUser("lp03");
    String ch = seedChannel(owner, "lp03", false);
    UNFURL.programServerError();

    var body =
        client(owner)
            .post(
                "/channels/" + ch + "/messages",
                RelayClient.body("text", "see " + UNFURL.baseUrl() + "/unfurl"))
            .expectStatus(201)
            .json();
    assertThat(body.path("linkPreviewTitle").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "S-LP-04 [G-HTTP]: 5 failing posts open breaker; 6th → 201 null AND upstream count == 5")
  void breakerOpensAfterFive() {
    String owner = seedUser("lp04");
    String ch = seedChannel(owner, "lp04", false);
    UNFURL.programServerError();
    String url = UNFURL.baseUrl() + "/unfurl";

    for (int i = 0; i < 5; i++) {
      client(owner)
          .post("/channels/" + ch + "/messages", RelayClient.body("text", "post " + i + " " + url))
          .expectStatus(201);
    }
    var body =
        client(owner)
            .post("/channels/" + ch + "/messages", RelayClient.body("text", "post 6 " + url))
            .expectStatus(201)
            .json();
    assertThat(body.path("linkPreviewTitle").isNull()).isTrue();
    assertThat(UNFURL.requestCount()).as("breaker open → no 6th upstream call").isEqualTo(5);
  }

  @Test
  @DisplayName("S-LP-05: GET /links/preview → 200 {title}; upstream 500 → 502; missing url → 422")
  void syncProxy() {
    String owner = seedUser("lp05");
    String url = UNFURL.baseUrl() + "/unfurl";

    UNFURL.programOk("Synced");
    assertThat(
            client(owner)
                .get(
                    "/links/preview?url="
                        + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8))
                .expectStatus(200)
                .string("title"))
        .isEqualTo("Synced");

    UNFURL.programServerError();
    client(owner)
        .get(
            "/links/preview?url="
                + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8))
        .expectStatus(502)
        .expectCode("unfurl:upstream_failed");

    client(owner).get("/links/preview").expectStatus(422);
  }
}
