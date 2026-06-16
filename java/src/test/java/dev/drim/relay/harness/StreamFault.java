package dev.drim.relay.harness;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only fault control (04-dependencies.md §8): armed to "fail after N", the streaming RPC
 * writes N statuses then aborts mid-stream with a gRPC error — the deterministic partial-stream
 * probe. Unset (the production default) → the stream always completes cleanly; the service code
 * path is identical either way. Mirrors presence.StreamFault in go/src/relay/presence/service.go.
 */
public final class StreamFault {
  // 0 = disarmed (sentinel); an armed value is N+1.
  private final AtomicInteger failAfter = new AtomicInteger(0);

  /** Arms the fault: the next stream emits {@code messages} statuses then aborts. */
  public void failAfter(int messages) {
    failAfter.set(messages + 1);
  }

  /** Disarms the fault. */
  public void clear() {
    failAfter.set(0);
  }

  /** Returns the limit (statuses before abort) and whether the fault is armed. */
  Limit limit() {
    int v = failAfter.get();
    if (v == 0) {
      return new Limit(0, false);
    }
    return new Limit(v - 1, true);
  }

  record Limit(int value, boolean armed) {}
}
