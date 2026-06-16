package dev.drim.relay.naive;

import dev.drim.relay.app.SummaryPrompt;
import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.Seams.SummarySource;
import dev.drim.relay.seams.Summarizer;
import dev.drim.relay.seams.SummaryModel;
import java.util.List;

/**
 * The G-LLM naive variant (exhibit, NEVER in src/): it concatenates raw user text into the
 * INSTRUCTION prompt (prompt injection) and returns the model output UNVALIDATED — no length /
 * empty check. So oversized or empty model output is forwarded as a 200 instead of the correct 502
 * summary:invalid_output, and hostile user text lands in the system prompt. Caught by SummaryTest
 * S-SM-03/04/05 + GLlmNaiveDemoTest.
 */
public final class NaiveSummarizer implements Summarizer {
  private final SummaryModel model;

  public NaiveSummarizer(SummaryModel model) {
    this.model = model;
  }

  @Override
  public String summarize(List<SummarySource> sources) {
    StringBuilder injected = new StringBuilder(SummaryPrompt.SYSTEM_PROMPT);
    for (SummarySource s : sources) {
      // The bug: raw user text spliced into the instruction prompt — no delimited data block.
      injected.append('\n').append(s.handle()).append(": ").append(s.text());
    }
    // The bug: output returned with no non-empty / length validation.
    return model.complete(new Events.SummaryRequest(injected.toString(), List.of()));
  }
}
