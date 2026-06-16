package dev.drim.relay.infra;

import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.SummaryModel;
import dev.drim.relay.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * The production default for the LLM port: the companion ships without model credentials (the port
 * is the architectural boundary; the test harness attaches an interaction-verifying FAKE here as a
 * {@code @Primary} override). 503 if ever called unconfigured. Mirrors notConfiguredSummary in
 * go/src/relay/app/build.go.
 */
@Component
public class NotConfiguredSummaryModel implements SummaryModel {
  @Override
  public String complete(Events.SummaryRequest request) {
    throw ApiException.unavailable("summary:unconfigured", "No summary model is configured.");
  }
}
