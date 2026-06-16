package dev.drim.relay.seams;

import dev.drim.relay.seams.Seams.SummarySource;
import java.util.List;

/**
 * The G-LLM seam: assembles the model request and VALIDATES the output. The correct implementation
 * keeps instructions and user content separated (prompt injection) and rejects contract-violating
 * output with 502 (never forwards it). The naive variant concatenates raw message text into the
 * instruction prompt and returns output unvalidated.
 */
public interface Summarizer {
  String summarize(List<SummarySource> sources);
}
