package dev.drim.relay.harness;

import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.SummaryModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The canonical FAKE (04-dependencies.md §6): the LLM is nondeterministic, paid, and external, so
 * the boundary is a deliberate in-process double, not a container. Seed = program the next response
 * (canned / empty / oversized); Assert = interaction verification — the captured request is where
 * the prompt-injection catch lives; Reset = clear responses + captured calls. Hand-rolled on
 * purpose (no mocking framework) so the pattern reads cross-language. Mirrors go/harness/llm.go.
 */
public final class LlmHarness implements DependencyHarness {
  private final Object lock = new Object();
  private final Deque<String> programmed = new ArrayDeque<>();
  private final List<Events.SummaryRequest> captured = new ArrayList<>();

  @Override
  public void start() {}

  @Override
  public void reset() {
    clear();
  }

  @Override
  public void stop() {}

  /** Returns the fake as the app's {@link SummaryModel} seam. */
  public SummaryModel model() {
    return new FakeSummaryModel();
  }

  /** Seeds the next response (FIFO). Unprogrammed → a canned summary. */
  public void programResponse(String response) {
    synchronized (lock) {
      programmed.addLast(response);
    }
  }

  /** Returns the requests the app made — for interaction verification. */
  public List<Events.SummaryRequest> capturedRequests() {
    synchronized (lock) {
      return new ArrayList<>(captured);
    }
  }

  public void clear() {
    synchronized (lock) {
      programmed.clear();
      captured.clear();
    }
  }

  private final class FakeSummaryModel implements SummaryModel {
    @Override
    public String complete(Events.SummaryRequest request) {
      synchronized (lock) {
        captured.add(request);
        if (!programmed.isEmpty()) {
          return programmed.removeFirst();
        }
        return "(canned summary)";
      }
    }
  }
}
