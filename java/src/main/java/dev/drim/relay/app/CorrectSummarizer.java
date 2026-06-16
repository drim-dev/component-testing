package dev.drim.relay.app;

import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.Seams.SummarySource;
import dev.drim.relay.seams.Summarizer;
import dev.drim.relay.seams.SummaryModel;
import dev.drim.relay.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The correct G-LLM seam: instructions ONLY in the system prompt, messages ONLY as delimited data
 * blocks, and the model output VALIDATED (non-empty, ≤ 2000 code points) before returning — else
 * 502, never forwarding garbage. The naive variant concatenates raw text into the instruction
 * prompt and returns output unvalidated.
 */
@Component
public class CorrectSummarizer implements Summarizer {
  private final SummaryModel model;

  public CorrectSummarizer(SummaryModel model) {
    this.model = model;
  }

  @Override
  public String summarize(List<SummarySource> sources) {
    List<String> blocks =
        sources.stream().map(s -> SummaryPrompt.renderBlock(s.handle(), s.text())).toList();
    String out = model.complete(new Events.SummaryRequest(SummaryPrompt.SYSTEM_PROMPT, blocks));
    if (out == null
        || out.isBlank()
        || out.codePointCount(0, out.length()) > SummaryPrompt.MAX_SUMMARY_LENGTH) {
      throw ApiException.upstream(
          "summary:invalid_output", "The model violated the summary output contract.");
    }
    return out;
  }
}
