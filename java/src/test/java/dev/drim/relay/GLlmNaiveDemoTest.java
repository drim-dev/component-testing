package dev.drim.relay;

import dev.drim.relay.naive.NaiveSummarizer;
import dev.drim.relay.seams.Summarizer;
import dev.drim.relay.seams.SummaryModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-LLM naive red→green demonstration: wires {@link NaiveSummarizer} (raw text into the instruction
 * prompt, no output validation) and confirms S-SM-04's catch goes RED — an oversized model output
 * is forwarded as a 200 instead of the correct 502 summary:invalid_output.
 */
@Import(GLlmNaiveDemoTest.NaiveConfig.class)
class GLlmNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-LLM naive demo: catch S-SM-04 goes red against NaiveSummarizer")
  void naiveSummarizerForwardsOversizedOutput() {
    String owner = seedUser("gllm");
    String ch = seedChannel(owner, "gllm", false);
    client(owner)
        .post("/channels/" + ch + "/messages", RelayClient.body("text", "hi"))
        .expectStatus(201);

    NaiveDemoSupport.expectCatchToFail(
        "G-LLM",
        () -> {
          LLM.programResponse("x".repeat(5000));
          client(owner)
              .post("/channels/" + ch + "/summary", RelayClient.body())
              .expectStatus(502)
              .expectCode("summary:invalid_output");
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    Summarizer naiveSummarizer(SummaryModel model) {
      return new NaiveSummarizer(model);
    }
  }
}
