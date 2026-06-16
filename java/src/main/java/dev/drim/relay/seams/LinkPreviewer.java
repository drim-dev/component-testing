package dev.drim.relay.seams;

import java.util.Optional;

/**
 * The G-HTTP seam: fetch a link title with timeout + circuit breaker; failure degrades to no title
 * (never escapes). The naive variant has no timeout/guard.
 */
public interface LinkPreviewer {
  /** Returns the title for url, or empty when the unfurl failed/degraded/breaker-open. */
  Optional<String> preview(String url);
}
