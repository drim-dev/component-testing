package dev.drim.relay.seams;

import dev.drim.relay.domain.Events;

/**
 * The LLM port (the canonical FAKE): the app never builds a prompt string inline in a handler —
 * everything crosses this port. The fake verifies the interaction (the captured request). A real
 * deployment registers an HTTP-backed model here.
 */
public interface SummaryModel {
  String complete(Events.SummaryRequest request);
}
