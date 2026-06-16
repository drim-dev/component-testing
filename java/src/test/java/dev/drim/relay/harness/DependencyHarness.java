package dev.drim.relay.harness;

/**
 * The central harness abstraction (04-dependencies.md §0): every dependency's harness answers the
 * same lifecycle. The per-dependency Seed / Assert / fault-control methods live on the concrete
 * classes (their shapes differ), so this interface is just the uniform lifecycle the composition
 * drives.
 *
 * <p>Extensibility here = add a harness class, not runtime re-composition (honest framing). Mirrors
 * the Go {@code DependencyHarness} interface (go/harness/dependencyharness.go).
 */
public interface DependencyHarness {
  /**
   * Brings up the real dependency (Testcontainers) or constructs the fake, and records the
   * connection config the app under test will use (as Spring properties).
   */
  void start();

  /** Returns the dependency to a clean state between tests, fast. */
  void reset();

  /** Tears the dependency down at suite end. */
  void stop();
}
