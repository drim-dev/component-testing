package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Identity (S-ID) and Users (S-US) scenarios from spec/06-acceptance.md, 1:1. Each test embeds its
 * scenario id in the {@link DisplayName} so the conformance grep finds it.
 */
class IdentityAndUsersTest extends AcceptanceTestBase {

  @Test
  @DisplayName("S-ID-01: any endpoint without X-User-Id → 401 identity:missing")
  void identityMissing() {
    anonymous().get("/channels").expectStatus(401).expectCode("identity:missing");
  }

  @Test
  @DisplayName("S-ID-02: X-User-Id of a non-existent user → 401 identity:unknown")
  void identityUnknown() {
    client("0000000000000").get("/channels").expectStatus(401).expectCode("identity:unknown");
  }

  @Test
  @DisplayName("S-US-01: POST /users valid → 201, echoes handle/displayName, id+createdAt present")
  void createUserValid() {
    RelayClient.Response resp =
        anonymous()
            .post("/users", RelayClient.body("handle", "alice", "displayName", "Alice"))
            .expectStatus(201);
    assertThat(resp.string("handle")).isEqualTo("alice");
    assertThat(resp.string("displayName")).isEqualTo("Alice");
    assertThat(resp.string("id")).isNotBlank();
    assertThat(resp.string("createdAt")).isNotBlank();
  }

  @Test
  @DisplayName("S-US-02: duplicate handle → 409 user:handle:taken")
  void duplicateHandle() {
    seedUser("bob");
    anonymous()
        .post("/users", RelayClient.body("handle", "bob", "displayName", "Bob Two"))
        .expectStatus(409)
        .expectCode("user:handle:taken");
  }

  @Test
  @DisplayName("S-US-03: invalid handle (ab / UPPER / has space) → 422 user:handle:invalid")
  void invalidHandle() {
    for (String bad : new String[] {"ab", "UPPER", "has space"}) {
      anonymous()
          .post("/users", RelayClient.body("handle", bad, "displayName", "X"))
          .expectStatus(422)
          .expectCode("user:handle:invalid");
    }
  }

  @Test
  @DisplayName("S-US-04: displayName empty / 65 chars → 422 user:display_name:invalid")
  void invalidDisplayName() {
    anonymous()
        .post("/users", RelayClient.body("handle", "carol", "displayName", ""))
        .expectStatus(422)
        .expectCode("user:display_name:invalid");
    anonymous()
        .post("/users", RelayClient.body("handle", "carol", "displayName", "x".repeat(65)))
        .expectStatus(422)
        .expectCode("user:display_name:invalid");
  }

  @Test
  @DisplayName("S-US-05: GET /users/{id} existing → 200")
  void getExistingUser() {
    String id = seedUser("dave");
    client(id).get("/users/" + id).expectStatus(200);
  }

  @Test
  @DisplayName("S-US-06: GET /users/{id} unknown → 404 user:not_found")
  void getUnknownUser() {
    String caller = seedUser("erin");
    client(caller).get("/users/0000000000000").expectStatus(404).expectCode("user:not_found");
  }
}
