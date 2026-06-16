package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.naive.NaiveLinkPreviewer;
import dev.drim.relay.seams.LinkPreviewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-HTTP naive red→green demonstration: wires {@link NaiveLinkPreviewer} (no timeout, no breaker,
 * failure escapes) and confirms S-LP-03's catch goes RED — a 500 from the upstream propagates out
 * of the post handler, turning a message post into a 500 instead of a 201 with a null preview.
 */
@Import(GHttpNaiveDemoTest.NaiveConfig.class)
class GHttpNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-HTTP naive demo: catch S-LP-03 goes red against NaiveLinkPreviewer")
  void naivePreviewerLetsFailureEscape() {
    String owner = seedUser("ghttp");
    String ch = seedChannel(owner, "ghttp", false);
    UNFURL.programServerError();

    NaiveDemoSupport.expectCatchToFail(
        "G-HTTP",
        () -> {
          var body =
              client(owner)
                  .post(
                      "/channels/" + ch + "/messages",
                      RelayClient.body("text", "see " + UNFURL.baseUrl() + "/unfurl"))
                  .expectStatus(201)
                  .json();
          assertThat(body.path("linkPreviewTitle").isNull()).isTrue();
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    LinkPreviewer naiveLinkPreviewer() {
      return new NaiveLinkPreviewer();
    }
  }
}
