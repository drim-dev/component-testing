package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Channels (S-CH) from spec/06-acceptance.md, 1:1. Carries the channel authorization gallery:
 * G-BOLA-READ (private existence hidden as 404, public messages 403), G-BOLA-ROLE (member cannot
 * add/kick/delete), and G-CACHE (kick invalidates the membership cache the very next read).
 */
class ChannelsTest extends AcceptanceTestBase {

  @Test
  @DisplayName("S-CH-01: create → 201; creator is sole owner (DB-state assert)")
  void createChannel() {
    String owner = seedUser("ch01");
    String id = seedChannel(owner, "general", false);
    assertThat(DATABASE.count("channel_members", "channel_id = '" + id + "'")).isEqualTo(1);
    assertThat(
            DATABASE.count(
                "channel_members",
                "channel_id = '" + id + "' AND user_id = '" + owner + "' AND role = 'owner'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("S-CH-02: name empty / 101 chars → 422 channel:name:invalid")
  void invalidName() {
    String owner = seedUser("ch02");
    client(owner)
        .post("/channels", RelayClient.body("name", "", "private", false))
        .expectStatus(422)
        .expectCode("channel:name:invalid");
    client(owner)
        .post("/channels", RelayClient.body("name", "x".repeat(101), "private", false))
        .expectStatus(422)
        .expectCode("channel:name:invalid");
  }

  @Test
  @DisplayName("S-CH-03: GET /channels → public + caller's private; nobody else's private")
  void listVisibleChannels() {
    String owner = seedUser("ch03a");
    String other = seedUser("ch03b");
    String pub = seedChannel(owner, "pub3", false);
    String myPriv = seedChannel(other, "mypriv3", true);
    String otherPriv = seedChannel(owner, "otherpriv3", true);

    JsonNode list = client(other).get("/channels").expectStatus(200).json();
    List<String> ids = list.path("items").findValuesAsText("id");
    assertThat(ids).contains(pub, myPriv).doesNotContain(otherPriv);
  }

  @Test
  @DisplayName("S-CH-04: non-member GET /channels/{public} → 200 metadata with memberCount")
  void getPublicMetadata() {
    String owner = seedUser("ch04a");
    String nonMember = seedUser("ch04b");
    String pub = seedChannel(owner, "pub4", false);
    JsonNode body = client(nonMember).get("/channels/" + pub).expectStatus(200).json();
    assertThat(body.path("memberCount").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "S-CH-05 [G-BOLA-READ]: non-member GET /channels/{private} → 404, byte-identical 404")
  void getPrivateHidden() {
    String owner = seedUser("ch05a");
    String nonMember = seedUser("ch05b");
    String priv = seedChannel(owner, "priv5", true);

    String hidden = client(nonMember).get("/channels/" + priv).expectStatus(404).bodyString();
    String unknown =
        client(nonMember).get("/channels/0000000000000").expectStatus(404).bodyString();
    assertThat(hidden).isEqualTo(unknown);
  }

  @Test
  @DisplayName("S-CH-06: join public → 201 membership role member")
  void joinPublic() {
    String owner = seedUser("ch06a");
    String joiner = seedUser("ch06b");
    String pub = seedChannel(owner, "pub6", false);
    JsonNode body =
        client(joiner).post("/channels/" + pub + "/join", null).expectStatus(201).json();
    assertThat(body.path("role").asText()).isEqualTo("member");
  }

  @Test
  @DisplayName("S-CH-07: join when already member → 409 channel:member:already")
  void joinAlready() {
    String owner = seedUser("ch07");
    String pub = seedChannel(owner, "pub7", false);
    client(owner)
        .post("/channels/" + pub + "/join", null)
        .expectStatus(409)
        .expectCode("channel:member:already");
  }

  @Test
  @DisplayName("S-CH-08: join private as non-member → 404")
  void joinPrivate() {
    String owner = seedUser("ch08a");
    String joiner = seedUser("ch08b");
    String priv = seedChannel(owner, "priv8", true);
    client(joiner).post("/channels/" + priv + "/join", null).expectStatus(404);
  }

  @Test
  @DisplayName("S-CH-09: owner adds user to private channel → 201")
  void ownerAddsMember() {
    String owner = seedUser("ch09a");
    String target = seedUser("ch09b");
    String priv = seedChannel(owner, "priv9", true);
    client(owner)
        .post("/channels/" + priv + "/members", RelayClient.body("userId", target))
        .expectStatus(201);
  }

  @Test
  @DisplayName("S-CH-10: admin adds user → 201")
  void adminAddsMember() {
    String owner = seedUser("ch10a");
    String admin = seedUser("ch10b");
    String target = seedUser("ch10c");
    String ch = seedChannel(owner, "ch10", false);
    seedMember(owner, ch, admin);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin + "/promote", null)
        .expectStatus(200);
    client(admin)
        .post("/channels/" + ch + "/members", RelayClient.body("userId", target))
        .expectStatus(201);
  }

  @Test
  @DisplayName(
      "S-CH-11 [G-BOLA-ROLE]: plain member adds user → 403; DB-state: no membership written")
  void memberCannotAdd() {
    String owner = seedUser("ch11a");
    String member = seedUser("ch11b");
    String target = seedUser("ch11c");
    String ch = seedChannel(owner, "ch11", false);
    seedMember(owner, ch, member);

    client(member)
        .post("/channels/" + ch + "/members", RelayClient.body("userId", target))
        .expectStatus(403)
        .expectCode("channel:role:forbidden");
    assertThat(
            DATABASE.count(
                "channel_members", "channel_id = '" + ch + "' AND user_id = '" + target + "'"))
        .isZero();
  }

  @Test
  @DisplayName("S-CH-12: add an existing member → 409")
  void addExistingMember() {
    String owner = seedUser("ch12a");
    String member = seedUser("ch12b");
    String ch = seedChannel(owner, "ch12", false);
    seedMember(owner, ch, member);
    client(owner)
        .post("/channels/" + ch + "/members", RelayClient.body("userId", member))
        .expectStatus(409);
  }

  @Test
  @DisplayName("S-CH-13: owner promotes member → 200 admin; admin attempts promote → 403")
  void promote() {
    String owner = seedUser("ch13a");
    String admin = seedUser("ch13b");
    String member = seedUser("ch13c");
    String ch = seedChannel(owner, "ch13", false);
    seedMember(owner, ch, admin);
    seedMember(owner, ch, member);

    JsonNode body =
        client(owner)
            .post("/channels/" + ch + "/members/" + admin + "/promote", null)
            .expectStatus(200)
            .json();
    assertThat(body.path("role").asText()).isEqualTo("admin");

    client(admin)
        .post("/channels/" + ch + "/members/" + member + "/promote", null)
        .expectStatus(403);
  }

  @Test
  @DisplayName("S-CH-14: admin kicks member → 204; membership gone")
  void adminKicks() {
    String owner = seedUser("ch14a");
    String admin = seedUser("ch14b");
    String member = seedUser("ch14c");
    String ch = seedChannel(owner, "ch14", false);
    seedMember(owner, ch, admin);
    seedMember(owner, ch, member);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin + "/promote", null)
        .expectStatus(200);

    client(admin).delete("/channels/" + ch + "/members/" + member).expectStatus(204);
    assertThat(
            DATABASE.count(
                "channel_members", "channel_id = '" + ch + "' AND user_id = '" + member + "'"))
        .isZero();
  }

  @Test
  @DisplayName("S-CH-15 [G-BOLA-ROLE]: member kicks member → 403; membership intact")
  void memberCannotKick() {
    String owner = seedUser("ch15a");
    String m1 = seedUser("ch15b");
    String m2 = seedUser("ch15c");
    String ch = seedChannel(owner, "ch15", false);
    seedMember(owner, ch, m1);
    seedMember(owner, ch, m2);

    client(m1).delete("/channels/" + ch + "/members/" + m2).expectStatus(403);
    assertThat(
            DATABASE.count(
                "channel_members", "channel_id = '" + ch + "' AND user_id = '" + m2 + "'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "S-CH-16 [G-CACHE]: read warms cache, owner kicks B, B reads again → 404 + cache invalidated")
  void cacheInvalidationOnKick() {
    String owner = seedUser("ch16a");
    String b = seedUser("ch16b");
    String priv = seedChannel(owner, "priv16", true);
    seedMember(owner, priv, b);

    // Warm the membership cache to the state a prior read would have left (the harness seeds the
    // same members:{channelId} key the app's cache uses — mirrors the Go
    // assertKickInvalidatesCache).
    client(b).get("/channels/" + priv + "/messages").expectStatus(200);
    REDIS.seedMembershipCache(priv, owner, b);
    assertThat(REDIS.cacheMember(priv, b).member()).as("cache warmed with B").isTrue();

    client(owner).delete("/channels/" + priv + "/members/" + b).expectStatus(204);

    client(b).get("/channels/" + priv + "/messages").expectStatus(404);
    assertThat(REDIS.cacheMember(priv, b).member())
        .as("membership cache invalidated for B")
        .isFalse();
  }

  @Test
  @DisplayName("S-CH-17: member leaves → 204; owner leaves → 409 channel:owner:cannot_leave")
  void leave() {
    String owner = seedUser("ch17a");
    String member = seedUser("ch17b");
    String ch = seedChannel(owner, "ch17", false);
    seedMember(owner, ch, member);

    client(member).delete("/channels/" + ch + "/members/" + member).expectStatus(204);
    client(owner)
        .delete("/channels/" + ch + "/members/" + owner)
        .expectStatus(409)
        .expectCode("channel:owner:cannot_leave");
  }

  @Test
  @DisplayName("S-CH-18: owner kicks admin → 204; admin kicks admin → 403")
  void ownerKicksAdmin() {
    String owner = seedUser("ch18a");
    String admin1 = seedUser("ch18b");
    String admin2 = seedUser("ch18c");
    String ch = seedChannel(owner, "ch18", false);
    seedMember(owner, ch, admin1);
    seedMember(owner, ch, admin2);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin1 + "/promote", null)
        .expectStatus(200);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin2 + "/promote", null)
        .expectStatus(200);

    String admin3 = seedUser("ch18d");
    seedMember(owner, ch, admin3);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin3 + "/promote", null)
        .expectStatus(200);

    client(owner).delete("/channels/" + ch + "/members/" + admin1).expectStatus(204);
    client(admin2).delete("/channels/" + ch + "/members/" + admin3).expectStatus(403);
  }

  @Test
  @DisplayName("S-CH-19 [G-BOLA-ROLE]: admin deletes → 403; member deletes → 403; channel intact")
  void onlyOwnerDeletes() {
    String owner = seedUser("ch19a");
    String admin = seedUser("ch19b");
    String member = seedUser("ch19c");
    String ch = seedChannel(owner, "ch19", false);
    seedMember(owner, ch, admin);
    seedMember(owner, ch, member);
    client(owner)
        .post("/channels/" + ch + "/members/" + admin + "/promote", null)
        .expectStatus(200);

    client(admin).delete("/channels/" + ch).expectStatus(403);
    client(member).delete("/channels/" + ch).expectStatus(403);
    assertThat(DATABASE.count("channels", "id = '" + ch + "'")).isEqualTo(1);
  }

  @Test
  @DisplayName("S-CH-20: owner deletes → 204; messages/memberships/attachments gone; GET → 404")
  void ownerDeletes() {
    String owner = seedUser("ch20");
    String ch = seedChannel(owner, "ch20", false);
    client(owner)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "hi"))
        .expectStatus(201);

    client(owner).delete("/channels/" + ch).expectStatus(204);
    assertThat(DATABASE.count("channel_messages", "channel_id = '" + ch + "'")).isZero();
    assertThat(DATABASE.count("channel_members", "channel_id = '" + ch + "'")).isZero();
    client(owner).get("/channels/" + ch).expectStatus(404);
  }

  @Test
  @DisplayName(
      "S-CH-21 [G-BOLA-READ]: non-member GET /channels/{private}/messages → 404; no items leak")
  void privateMessagesHidden() {
    String owner = seedUser("ch21a");
    String nonMember = seedUser("ch21b");
    String priv = seedChannel(owner, "priv21", true);
    client(owner)
        .post("/channels/" + priv + "/messages", RelayClient.body("text", "secret"))
        .expectStatus(201);

    String hidden =
        client(nonMember).get("/channels/" + priv + "/messages").expectStatus(404).bodyString();
    assertThat(hidden).doesNotContain("secret");
  }

  @Test
  @DisplayName(
      "S-CH-22: non-member GET /channels/{public}/messages → 403 channel:membership_required")
  void publicMessagesNeedMembership() {
    String owner = seedUser("ch22a");
    String nonMember = seedUser("ch22b");
    String pub = seedChannel(owner, "pub22", false);
    client(nonMember)
        .get("/channels/" + pub + "/messages")
        .expectStatus(403)
        .expectCode("channel:membership_required");
  }

  @Test
  @DisplayName(
      "S-CH-23: member posts → 201; non-member posts: public 403, private 404; nothing written")
  void postPermissions() {
    String owner = seedUser("ch23a");
    String nonMember = seedUser("ch23b");
    String pub = seedChannel(owner, "pub23", false);
    String priv = seedChannel(owner, "priv23", true);

    client(owner)
        .post("/channels/" + pub + "/messages", RelayClient.body("text", "ok"))
        .expectStatus(201);
    client(nonMember)
        .post("/channels/" + pub + "/messages", RelayClient.body("text", "x"))
        .expectStatus(403);
    client(nonMember)
        .post("/channels/" + priv + "/messages", RelayClient.body("text", "x"))
        .expectStatus(404);
    assertThat(DATABASE.count("channel_messages", "sender_id = '" + nonMember + "'")).isZero();
  }

  @Test
  @DisplayName("S-CH-24: post text empty / 4001 chars → 422; 11 attachmentIds → 422")
  void postValidation() {
    String owner = seedUser("ch24");
    String ch = seedChannel(owner, "ch24", false);

    client(owner)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", ""))
        .expectStatus(422);
    client(owner)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "x".repeat(4001)))
        .expectStatus(422);
    client(owner)
        .post(
            "/channels/" + ch + "/messages",
            RelayClient.body("text", "hi", "attachmentIds", java.util.Collections.nCopies(11, "a")))
        .expectStatus(422);
  }
}
