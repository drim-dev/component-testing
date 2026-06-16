package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attachments (S-AT) from spec/06-acceptance.md, 1:1. Carries G-S3 (S-AT-06/07): download
 * authorization is by channel membership, never key possession — a non-member gets the existence
 * split (private 404 / public 403) with zero bytes, exactly like the channel read gate.
 */
class AttachmentsTest extends AcceptanceTestBase {

  private String upload(String userId, String channelId, String filename, byte[] content) {
    return client(userId)
        .postFile("/channels/" + channelId + "/attachments", filename, content)
        .expectStatus(201)
        .string("id");
  }

  @Test
  @DisplayName(
      "S-AT-01: member uploads 10 KiB → 201; bytes in MinIO under storage_key; row correct")
  void upload() {
    String owner = seedUser("at01");
    String ch = seedChannel(owner, "at01", false);
    byte[] content = new byte[10 * 1024];
    java.util.Arrays.fill(content, (byte) 7);

    String attId = upload(owner, ch, "doc.bin", content);
    assertThat(S3.objectBytes(ch + "/" + attId)).isEqualTo(content);
    assertThat(
            DATABASE.count(
                "attachments", "id = '" + attId + "' AND size_bytes = " + content.length))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("S-AT-02: non-member upload: public → 403, private → 404")
  void nonMemberUpload() {
    String owner = seedUser("at02a");
    String nonMember = seedUser("at02b");
    String pub = seedChannel(owner, "at02pub", false);
    String priv = seedChannel(owner, "at02priv", true);

    client(nonMember)
        .postFile("/channels/" + pub + "/attachments", "f", new byte[] {1})
        .expectStatus(403);
    client(nonMember)
        .postFile("/channels/" + priv + "/attachments", "f", new byte[] {1})
        .expectStatus(404);
  }

  @Test
  @DisplayName("S-AT-03: > 1 MiB → 413 attachment:too_large; empty → 422 attachment:empty")
  void sizeBounds() {
    String owner = seedUser("at03");
    String ch = seedChannel(owner, "at03", false);

    byte[] tooLarge = new byte[1024 * 1024 + 1];
    client(owner)
        .postFile("/channels/" + ch + "/attachments", "big", tooLarge)
        .expectStatus(413)
        .expectCode("attachment:too_large");
    client(owner)
        .postFile("/channels/" + ch + "/attachments", "empty", new byte[0])
        .expectStatus(422)
        .expectCode("attachment:empty");
  }

  @Test
  @DisplayName(
      "S-AT-04: post referencing own uploaded attachment → 201; foreign/other-channel → 422")
  void referenceAttachment() {
    String owner = seedUser("at04a");
    String other = seedUser("at04b");
    String ch1 = seedChannel(owner, "at04c1", false);
    String ch2 = seedChannel(owner, "at04c2", false);
    seedMember(owner, ch1, other);

    String mine = upload(owner, ch1, "mine.bin", new byte[] {1, 2, 3});
    client(owner)
        .post(
            "/channels/" + ch1 + "/messages",
            RelayClient.body("text", "see file", "attachmentIds", java.util.List.of(mine)))
        .expectStatus(201);

    String othersFile = upload(other, ch1, "theirs.bin", new byte[] {4});
    client(owner)
        .post(
            "/channels/" + ch1 + "/messages",
            RelayClient.body("text", "x", "attachmentIds", java.util.List.of(othersFile)))
        .expectStatus(422)
        .expectCode("message:attachment:invalid");

    String otherChannelFile = upload(owner, ch2, "other.bin", new byte[] {5});
    client(owner)
        .post(
            "/channels/" + ch1 + "/messages",
            RelayClient.body("text", "x", "attachmentIds", java.util.List.of(otherChannelFile)))
        .expectStatus(422)
        .expectCode("message:attachment:invalid");
  }

  @Test
  @DisplayName("S-AT-05: member downloads → 200, bytes identical, filename in Content-Disposition")
  void download() {
    String owner = seedUser("at05");
    String ch = seedChannel(owner, "at05", false);
    byte[] content = "download-me".getBytes();
    String attId = upload(owner, ch, "report.txt", content);

    RelayClient.Response resp = client(owner).get("/attachments/" + attId).expectStatus(200);
    assertThat(resp.bytes()).isEqualTo(content);
    assertThat(resp.header("Content-Disposition")).contains("report.txt");
  }

  @Test
  @DisplayName(
      "S-AT-06 [G-S3]: non-member downloads private attachment → 404, zero bytes, identical 404")
  void privateDownloadHidden() {
    String owner = seedUser("at06a");
    String nonMember = seedUser("at06b");
    String priv = seedChannel(owner, "at06priv", true);
    String attId = upload(owner, priv, "secret.bin", new byte[] {9, 9});

    RelayClient.Response hidden = client(nonMember).get("/attachments/" + attId).expectStatus(404);
    assertThat(hidden.bytes()).isNotEqualTo(new byte[] {9, 9});
    String unknown =
        client(nonMember).get("/attachments/0000000000000").expectStatus(404).bodyString();
    assertThat(hidden.bodyString()).isEqualTo(unknown);
  }

  @Test
  @DisplayName(
      "S-AT-07 [G-S3]: non-member downloads public attachment → 403 membership_required, zero bytes")
  void publicDownloadForbidden() {
    String owner = seedUser("at07a");
    String nonMember = seedUser("at07b");
    String pub = seedChannel(owner, "at07pub", false);
    String attId = upload(owner, pub, "doc.bin", new byte[] {1, 2});

    RelayClient.Response resp =
        client(nonMember)
            .get("/attachments/" + attId)
            .expectStatus(403)
            .expectCode("channel:membership_required");
    assertThat(resp.bytes()).isNotEqualTo(new byte[] {1, 2});
  }
}
