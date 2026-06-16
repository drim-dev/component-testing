// The expect-failure wrapper (05-gallery §0.4, gate item) — the Vitest idiom. It
// runs the catching test's OWN assertion block against an app where the gallery
// case's correct seam has been replaced by its naive variant, and asserts those
// assertions FAIL — i.e. the catching test goes red against the bug. This keeps a
// RED demonstration executable inside a GREEN suite.
//
// In Vitest a failed assertion (or a thrown ApiError surfacing the buggy
// response) throws. The wrapper runs the block and:
//   - if the block THROWS, the naive variant was caught → the demo passes;
//   - if the block RESOLVES, the catching test did NOT catch this gallery case (a
//     false proof) → the wrapper itself throws.
//
// Each gallery case ships two tests sharing one assertion helper: the catching
// test (correct app → green) and the naive demo (expectCatchToFail → green
// *because* the catch goes red).

export async function expectCatchToFail(
  galleryCaseId: string,
  catchingAssertions: () => Promise<void>,
): Promise<void> {
  let caught = false;
  try {
    await catchingAssertions();
  } catch {
    caught = true;
  }
  if (!caught) {
    throw new Error(
      `naive-variant demonstration for ${galleryCaseId} did NOT go red: the catching ` +
        'assertions passed against the naive (buggy) implementation. The catching test is ' +
        'not actually catching this gallery case.',
    );
  }
}
