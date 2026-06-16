#!/bin/sh
# lock-tests.sh — the hard enforcement layer of "кто пишет код — не пишет ему проверку".
#
# WHY THIS EXISTS
# ----------------
# A coding agent that authors both the code and the test that "verifies" it has
# closed the loop on itself: the test becomes a mirror that reflects the
# implementation back as "correct". The most dangerous version of this is not the
# agent writing a weak NEW test — it is the agent quietly REWRITING a PRE-EXISTING
# test so a previously-failing check goes green (ImpossibleBench: frontier models
# follow a broken test over the spec up to 76% of the time). A system-prompt rule
# ("do not edit existing tests") sets the expectation but is not trustworthy on its
# own. This script is the teeth: it refuses to let a change land if it modified a
# test file that already existed on the base branch — UNLESS a human signed off
# explicitly via a commit trailer.
#
# WHAT IT CHECKS
# ----------------
# Over the range <base>..HEAD it asks git which test files were Modified, Renamed,
# Deleted, or Copied-over (status M/R/D/C — i.e. files that existed on the base and
# changed). Files that are purely Added (status A) are the agent's own new tests for
# its own feature: those are allowed, by design (the agent MAY add tests; it MAY NOT
# touch tests it did not write). If any pre-existing test file changed, the build
# fails — except for files changed only by commits carrying a
# `Test-Edit-Approved: <reason>` trailer (the audited human override).
#
# LANGUAGE-AGNOSTIC BY CONSTRUCTION
# ----------------
# This is just `git` + path globs. It encodes nothing about any test framework, so
# the same script guards all five reference languages (and the reader's own repo,
# whatever stack it runs). The globs below cover every test-file convention in the
# companion; extend the case statement for your own.
#
# This file ships as a CITABLE RECIPE for the reader's own project. The companion
# repository itself runs no CI by design (Dima's decision — it is a frozen teaching
# exhibit, not a living product with agents committing to it), so nothing invokes
# this script here. The reader wires it into their own pipeline where agents
# continuously contribute and the gate earns its keep.

set -eu

# Base ref to diff against. CI usually passes the PR's target branch; locally you
# pass the fork point. Default to origin/main so a bare invocation does the obvious
# thing on a feature branch.
BASE_REF="${1:-${LOCK_TESTS_BASE:-origin/main}}"

# Resolve the merge base so we compare against where this branch DIVERGED, not the
# tip of the base branch — unrelated test edits that landed on main after we
# branched must not be blamed on us. (This is the same divergence point `git diff
# base...HEAD` uses; we compute it explicitly so the trailer scan below shares it.)
# Fall back to the raw ref if no common ancestor is found (e.g. unrelated histories).
if MERGE_BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null)"; then
    DIFF_BASE="$MERGE_BASE"
else
    DIFF_BASE="$BASE_REF"
fi

# is_test_path PATH -> exit 0 if PATH is a locked (pre-existing-eligible) test file.
#
# Two gates, both must hold:
#   1. the path is not inside a build-output / vendored directory — those can carry
#      generated files whose names collide with the test globs (e.g. .NET emits
#      obj/.../Relay.Api.Tests.AssemblyInfo.cs, which matches *Tests*.cs but is not a
#      hand-written test). Locking generated files would produce false reds.
#   2. the filename matches one of the five languages' test conventions.
is_test_path() {
    path="$1"

    case "$path" in
        */obj/*|obj/*|*/bin/*|bin/*|\
        */node_modules/*|node_modules/*|*/dist/*|dist/*|\
        */build/*|build/*|*/target/*|target/*|*/.next/*|.next/*)
            return 1
            ;;
    esac

    case "$path" in
        # .NET — xUnit; test projects/files carry "Tests" in the name and live under tests/.
        *Tests*.cs|*Tests/*.cs)                 return 0 ;;
        # Go — the compiler-enforced *_test.go convention.
        *_test.go)                              return 0 ;;
        # TypeScript — Vitest/Jest .spec.ts (covers the *.lying.spec.ts exhibits too).
        *.spec.ts)                              return 0 ;;
        # Java — JUnit *Test.java (covers *LyingTest.java).
        *Test.java)                             return 0 ;;
        # Python — pytest test_*.py (covers test_lying_*.py).
        test_*.py|*/test_*.py)                  return 0 ;;
    esac

    return 1
}

# Pre-existing test files touched in this range: status M(odified) R(enamed)
# D(eleted) C(opied) — every status EXCEPT A(dded). An added file is a brand-new
# test the agent is allowed to write; the others all imply a file that existed on
# the base was changed. --diff-filter excludes A for us.
changed_tests=""
for path in $(git diff --name-only --diff-filter=MRDC "$DIFF_BASE" HEAD); do
    if is_test_path "$path"; then
        changed_tests="$changed_tests $path"
    fi
done

if [ -z "${changed_tests# }" ]; then
    echo "lock-tests: OK — no pre-existing test files were modified in $DIFF_BASE..HEAD"
    exit 0
fi

# A pre-existing test changed. The only legitimate reason is an intentional, audited
# human decision recorded as a `Test-Edit-Approved: <reason>` trailer on the
# commit(s) that touched the test. We inspect, per offending file, the commits in
# range that touched it; if EVERY such commit carries the trailer, the change is
# approved. If any touching commit lacks it, the gate fails — an agent cannot forge
# the override because it cannot author a human-signed approval trailer in the
# review flow (and the trailer is visible in the audit log).
unapproved=""
for path in $changed_tests; do
    approved=1
    for sha in $(git log --format=%H "$DIFF_BASE..HEAD" -- "$path"); do
        if ! git show -s --format='%(trailers:key=Test-Edit-Approved,valueonly)' "$sha" \
            | grep -q '[^[:space:]]'; then
            approved=0
            break
        fi
    done
    if [ "$approved" -eq 1 ]; then
        echo "lock-tests: APPROVED override — $path (every touching commit carries Test-Edit-Approved)"
    else
        unapproved="$unapproved $path"
    fi
done

if [ -n "${unapproved# }" ]; then
    echo "lock-tests: FAIL — pre-existing test file(s) modified without a Test-Edit-Approved trailer:" >&2
    for path in $unapproved; do
        echo "  - $path" >&2
    done
    echo "" >&2
    echo "An agent must not rewrite tests it did not author. If this change is" >&2
    echo "intentional, a human re-commits it with a trailer, e.g.:" >&2
    echo "" >&2
    echo "    git commit --amend --trailer 'Test-Edit-Approved: pagination contract changed, see #123'" >&2
    exit 1
fi

echo "lock-tests: OK — all pre-existing test changes carry an approved override"
exit 0
