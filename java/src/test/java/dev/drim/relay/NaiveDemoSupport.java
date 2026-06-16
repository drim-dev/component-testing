package dev.drim.relay;

/**
 * The expect-failure wrapper (05-gallery.md §0.4 point 4): runs a catching test's own assertion
 * block against the naive-wired app and asserts it FAILS. This keeps a RED demonstration executable
 * inside a GREEN suite — if the catching assertions PASS against the naive variant, the catch would
 * be a false proof, so the wrapper itself fails. The Java analog of the .NET {@code
 * NaiveDemo.ExpectCatchToFail}.
 */
final class NaiveDemoSupport {
  private NaiveDemoSupport() {}

  static void expectCatchToFail(String caseId, Runnable catchingAssertions) {
    try {
      catchingAssertions.run();
    } catch (AssertionError expected) {
      return; // the catch went red against the naive variant — exactly the proof we want
    }
    throw new AssertionError(
        "Gallery "
            + caseId
            + ": the catching assertions PASSED against the naive variant — the catch is a false"
            + " proof (it would not detect the bug it claims to).");
  }
}
