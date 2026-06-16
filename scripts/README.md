# Test-file lock — the three layers

> **«Кто пишет код — не пишет ему проверку».**
> A coding agent that authors both the code and the test that "verifies" it has
> closed the loop on itself. The test becomes a *mirror*: it reflects the
> implementation back as "correct" and proves nothing. The sharpest form of this
> is the agent **rewriting a pre-existing test** so a failing check goes green
> (ImpossibleBench: frontier models follow a broken test over the spec up to 76%
> of the time). These files take that move away.

The lock is **three layers**, weakest first. No single layer is trusted alone;
each backs up the one before it.

## (a) The system-prompt rule — sets the expectation

The agent is told, in its system prompt:

> You may **add** new test files for the feature you are implementing. You may
> **not modify** any test file that already exists on `main`. If a pre-existing
> test goes red, **stop and report** — do not edit the test to make it pass.

This is necessary but **not sufficient**: a rule the agent can ignore, forget, or
rationalize around is not enforcement. It is the cheap, cooperative layer.

## (b) The CI gate — the hard enforcement

[`lock-tests.sh`](./lock-tests.sh), wired into CI via
[`../.github/workflows/lock-tests.yml`](../.github/workflows/lock-tests.yml), is
the teeth. It runs `git diff` over the test paths for the change and **fails the
build** if any pre-existing test file was modified, renamed, or deleted. A purely
*added* test file is fine (that is the agent's own test for its own feature); a
*changed* pre-existing one is not.

It is **language-agnostic** — just `git` + path globs — so the one script guards
all five reference languages and ports to any stack. The globs it locks:

| Language   | Test-file glob                          |
|------------|-----------------------------------------|
| .NET       | `*Tests*.cs` (incl. `*LyingTests.cs`)   |
| Go         | `*_test.go`                             |
| TypeScript | `*.spec.ts` (incl. `*.lying.spec.ts`)   |
| Java       | `*Test.java` (incl. `*LyingTest.java`)  |
| Python     | `test_*.py` (incl. `test_lying_*.py`)   |

Build-output and vendored directories (`obj/`, `bin/`, `node_modules/`, `dist/`,
`build/`, `target/`, `.next/`) are excluded — generated files there can collide
with the globs (e.g. .NET emits `obj/.../Relay.Api.Tests.AssemblyInfo.cs`, which
matches `*Tests*.cs` but is not a hand-written test) and would otherwise produce
false reds.

## (c) The `Test-Edit-Approved:` trailer — the audited human override

Sometimes a pre-existing test *legitimately* must change — the contract it pins
genuinely moved (e.g. a pagination bound was deliberately changed). The gate is
not a wall; it is a **checkpoint with a signature**. A human re-commits the test
edit with a commit trailer stating the reason:

```sh
git commit --amend --trailer 'Test-Edit-Approved: pagination contract changed, see #123'
```

The gate inspects the trailers on every commit that touched each offending test
file. If **every** such commit carries `Test-Edit-Approved: <reason>`, the change
is allowed and logged; if any touching commit lacks it, the gate fails. The
override is therefore **explicit, intentional, and auditable** — never silent. An
agent cannot forge it: the approval is a human-authored line in the commit trail,
visible in review and in `git log`.

## Usage

```sh
# Default: diff against the merge base with origin/main.
sh scripts/lock-tests.sh

# Explicit base ref (what CI does — pass the PR's target branch):
sh scripts/lock-tests.sh origin/main

# Or via env var:
LOCK_TESTS_BASE=origin/release-2.0 sh scripts/lock-tests.sh
```

Exit `0` = no illicit test change (or all changes approved); exit `1` = a
pre-existing test was modified without an approval trailer.

> **Note for this repo.** The companion runs no CI by design — it is a frozen
> teaching exhibit, not a product with agents committing to it. This recipe ships
> as something the **reader copies into their own pipeline**, where agents
> continuously contribute and the gate earns its keep. The script's logic is
> proven by running it locally (see the §8 prose).
