package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Presence / gRPC (S-PR) from spec/06-acceptance.md, 1:1. Carries G-GRPC (S-PR-04): a mid-stream
 * failure must surface as a 502 presence:incomplete with NO partial member list — a truncated
 * stream reported as complete is the catch.
 */
class PresenceTest extends AcceptanceTestBase {

  @Test
  @DisplayName("S-PR-01: presence reports B online → A GET /users/{B}/presence → 200 online (unary)")
  void unaryOnline() {
    String a = seedUser("pr01a");
    String b = seedUser("pr01b");
    PRESENCE.setOnline(b);
    assertThat(client(a).get("/users/" + b + "/presence").expectStatus(200).string("status"))
        .isEqualTo("online");
  }

  @Test
  @DisplayName("S-PR-02: no heartbeat for C → offline")
  void unaryOffline() {
    String a = seedUser("pr02a");
    String c = seedUser("pr02c");
    assertThat(client(a).get("/users/" + c + "/presence").expectStatus(200).string("status"))
        .isEqualTo("offline");
  }

  @Test
  @DisplayName("S-PR-03: channel of 5 (2 online): member → 200 with 5 entries, statuses correct")
  void channelStream() {
    String owner = seedUser("pr03owner");
    String ch = seedChannel(owner, "pr03", false);
    String[] members = new String[4];
    for (int i = 0; i < 4; i++) {
      members[i] = seedUser("pr03m" + i);
      seedMember(owner, ch, members[i]);
    }
    PRESENCE.setOnline(members[0]);
    PRESENCE.setOnline(members[1]);

    JsonNode body = client(owner).get("/channels/" + ch + "/presence").expectStatus(200).json();
    assertThat(body.path("members")).hasSize(5);
    long online = 0;
    for (JsonNode m : body.path("members")) {
      if (m.path("status").asText().equals("online")) {
        online++;
      }
    }
    assertThat(online).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "S-PR-04 [G-GRPC]: stream fails after 2 → channel presence 502 incomplete; no partial list")
  void streamFailureIsIncomplete() {
    String owner = seedUser("pr04owner");
    String ch = seedChannel(owner, "pr04", false);
    for (int i = 0; i < 4; i++) {
      seedMember(owner, ch, seedUser("pr04m" + i));
    }
    PRESENCE.failStreamAfter(2);

    RelayClient.Response resp =
        client(owner)
            .get("/channels/" + ch + "/presence")
            .expectStatus(502)
            .expectCode("presence:incomplete");
    assertThat(resp.bodyString()).doesNotContain("\"members\"");
  }

  @Test
  @DisplayName("S-PR-05: non-member channel presence: public → 403, private → 404")
  void nonMemberChannelPresence() {
    String owner = seedUser("pr05a");
    String nonMember = seedUser("pr05b");
    String pub = seedChannel(owner, "pr05pub", false);
    String priv = seedChannel(owner, "pr05priv", true);

    client(nonMember).get("/channels/" + pub + "/presence").expectStatus(403);
    client(nonMember).get("/channels/" + priv + "/presence").expectStatus(404);
  }
}
