package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.naive.NaiveAttachmentAccess;
import dev.drim.relay.seams.AttachmentAccess;
import dev.drim.relay.store.AttachmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-S3 naive red→green demonstration: wires {@link NaiveAttachmentAccess} (return by id, no
 * membership check) and confirms S-AT-06's catch goes RED — a non-member downloads a private
 * channel's attachment (200 + bytes) instead of the existence-hiding 404.
 */
@Import(GS3NaiveDemoTest.NaiveConfig.class)
class GS3NaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-S3 naive demo: catch S-AT-06 goes red against NaiveAttachmentAccess")
  void naiveAccessLeaksPrivateAttachment() {
    String owner = seedUser("gs3a");
    String nonMember = seedUser("gs3b");
    String priv = seedChannel(owner, "gs3priv", true);
    String attId =
        client(owner)
            .postFile("/channels/" + priv + "/attachments", "secret.bin", new byte[] {9, 9})
            .expectStatus(201)
            .string("id");

    NaiveDemoSupport.expectCatchToFail(
        "G-S3",
        () -> {
          RelayClient.Response hidden =
              client(nonMember).get("/attachments/" + attId).expectStatus(404);
          String unknown =
              client(nonMember).get("/attachments/0000000000000").expectStatus(404).bodyString();
          assertThat(hidden.bodyString()).isEqualTo(unknown);
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    AttachmentAccess naiveAttachmentAccess(AttachmentRepository attachments) {
      return new NaiveAttachmentAccess(attachments);
    }
  }
}
