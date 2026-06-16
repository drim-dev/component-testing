"""The expect-failure wrapper (05-gallery §0.4) — the pytest idiom.

It runs the catching test's OWN assertion block against an app where the gallery
case's correct seam has been replaced by its naive variant (via
``app.dependency_overrides``), and asserts those assertions FAIL — i.e. the
catching test goes red against the bug. This keeps a RED demonstration
executable inside a GREEN suite.

In pytest a failed assertion (or a thrown ApiError surfacing the buggy response)
raises. The wrapper runs the block and:
  - if the block RAISES, the naive variant was caught → the demo passes;
  - if the block RETURNS, the catching test did NOT catch this gallery case (a
    false proof) → the wrapper itself raises.

Each gallery case ships two tests sharing one assertion helper: the catching
test (correct app → green) and the naive demo (``expect_catch_to_fail`` → green
*because* the catch goes red).
"""

from __future__ import annotations

from collections.abc import Callable


def expect_catch_to_fail(gallery_case_id: str, catching_assertions: Callable[[], None]) -> None:
    caught = False
    try:
        catching_assertions()
    except Exception:
        caught = True
    if not caught:
        raise AssertionError(
            f"naive-variant demonstration for {gallery_case_id} did NOT go red: the catching "
            "assertions passed against the naive (buggy) implementation. The catching test is "
            "not actually catching this gallery case."
        )
