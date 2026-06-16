# 05 — The Bug Gallery

**Status:** Phase-0 spec. This file defines every gallery case: the correct
behavior, the **naive variant** (what an agent actually ships), the **lying
test** (green but useless), the **catching test** (component-level, through
the real assembled system), and the honesty note (where a unit test IS the
right tool). Design source: §11.C, §11.D, §11.H.

## 0. Ground rules (locked)

### 0.1 The app ships CORRECT (design §11.D, option A)

`src/` contains only correct code. Bugs are demonstrated **live**: each case's
catching test is paired with a demonstration that the same test **goes red**
against a *naive variant injected only inside that test*. No flags in the
product, no second git state.

- **Injection seam:** the naive variant replaces the correct implementation
  through the same seam the harness uses (DI override / constructor argument),
  scoped to one test. The naive variants live next to the tests (e.g.,
  `tests/naive/`), never in `src/`.
- **Expect-failure wrapper:** the red demonstration is executable while the
  suite stays green — a meta-assertion that the catching test's body FAILS
  against the naive variant (run the same assertion block against the
  naive-wired app and assert it throws / the response is the buggy one). The
  exact idiom per language is a **pilot-gate deliverable**; the .NET pilot
  documents the chosen mechanism in §0.4 below, Go proves it without a DI
  framework.
- **Framing (anti-"you planted it yourself"):** every naive variant below is
  sourced — it is the *default-shaped* code an agent ships for that feature
  (missing wiring, missing invalidation, fire-and-forget, no transaction),
  not an arbitrary mutation. The guide must present each as "what gets
  written when nobody pins this behavior," citing this spec.

### 0.2 Lying-test naming convention (locked — Dima, 2026-06-09)

Every lying test:

- carries `lying` in its identifier, per language: `*_lying_test.go`,
  `*.lying.spec.ts`, `*LyingTests` / `*LyingTest` (C#/Java),
  `test_lying_*.py`;
- opens with the header comment:
  `LYING TEST (exhibit, do not copy) — gallery case <ID>; caught by <catching test ref>`;
- never ships without its catcher: the README case index lists every lying
  test **paired** with its catching test.

Lying tests are real, runnable, and GREEN — that is the point. They are
exhibits of the agent's default test style, kept honest by the pairing.

### 0.3 Case record format

Each case: **Where it hides** (the natural `src/` location) · **Correct
behavior** (AC refs into `06-acceptance.md`) · **Naive variant** · **Lying
test** · **Catching test** · **Class** (emergent / pure-logic) · **Honesty
note** (what a unit test legitimately covers here).

### 0.4 Naive-variant injection mechanism (FILLED AT PILOT — gate item)

Recorded by the .NET pilot (`dotnet/`). The other four languages replicate this
shape with their own idiom; Go must prove it with NO DI framework.

**1. The seam.** Each injectable gallery case hides behind a single narrow
interface that the feature's handler depends on — the correct implementation
lives in `src/`, registered in DI:

| Case | Seam interface (`src/`) | Correct impl |
|---|---|---|
| G-IDOR | `IDmAccess` | participant-scoped read |
| G-RACE, G-TX | `IConversationWriter` | transactional + unique-conflict-handling create |
| G-BOLA-READ | `IChannelReadGate` | 404/403 visibility split |
| G-BOLA-ROLE | `IChannelRoleGate` | membership **and** role check |
| G-CACHE | `IMembershipWriter` | membership write + cache invalidation |
| G-KAFKA (producer) | `IMessagePostedPublisher` | publish awaiting broker confirmation |
| G-KAFKA (consumer) | `IFeedProjector` | idempotent insert + increment-on-first-insert |
| G-RABBIT | `INotificationRecorder` | insert treating duplicate as success (ack) |
| G-GRPC | `IPresenceClient` | consume stream to clean end; mid-stream error → 502 |

The handler 404s/403s based on what the seam returns/throws, so the
authorization decision is a property of the *assembled route*, not of a mock.

**2. The naive variants** live in `tests/Naive/` (never in `src/`), each
implementing the same seam interface with the default-shaped bug an agent ships
(`NaiveDmAccess` = load-by-id, `NaiveRaceConversationWriter` = check-then-insert,
`NaiveTxConversationWriter` = three saves no transaction, `NaiveChannelReadGate`
= ignore `private`, `NaiveChannelRoleGate` = skip the role compare).

**3. The injection seam (scoped to ONE test).** `TestFixture.NaiveClient<TService,
TNaive>(userId)` derives a one-off app from the shared factory via
`WithWebHostBuilder` + `ConfigureServices(s => { s.RemoveAll<TService>();
s.AddScoped<TService, TNaive>(); })`. This is `RemoveAll`+re-register — the .NET
idiom of the same seam the harness already uses. The derived host inherits the
shared container connection strings but runs on a **distinct IdGen generator id**
so its ids never collide with seeded data.

**4. The expect-failure wrapper.** `NaiveDemo.ExpectCatchToFail(caseId,
catchingAssertions)` runs the catching test's own assertion block against the
naive-wired client and asserts it FAILS (throws). If the assertions *pass*
against the naive variant, the wrapper itself throws — the catching test would
be a false proof. This keeps a RED demonstration executable inside a GREEN suite:
each gallery case ships two `[Fact]`s sharing one `Assert…` helper — the catching
test (correct app → green) and the naive demo (`ExpectCatchToFail` → green
*because* the catch goes red).

**5. Determinism note (G-RACE).** In-process requests can complete faster than the
natural TOCTOU window, so the naive check-then-insert variant adds a small
**test-only delay between the existence check and the insert**. This widens the
window deterministically without changing the *shape* of the bug (still a missing
unique-conflict handler). Permitted per the G-RACE record below. The correct
writer needs no such hook — its unique-conflict handling is timing-independent.

**6. TX fault injection.** The G-TX catching test arms a one-shot DB trigger
(`DatabaseHarness.ArmParticipantInsertFault`) that raises on the second
`dm_participants` insert. The correct transactional writer rolls everything back
(zero rows); the naive non-transactional writer leaves an orphan — which the
catching test reads. The trigger state is cleared by the per-test reset.

**7. gRPC partial-stream fault injection (G-GRPC).** The presence service is a
REAL companion-owned gRPC host — `PresenceHarness` boots the actual
`PresenceService` on an ephemeral HTTP/2 loopback port (`127.0.0.1:0`) over a
real socket, so the API consumes it through genuine gRPC (cleartext h2c), not an
in-process double. This is the transport-agnostic proof: the seam
(`IPresenceClient`) is swapped exactly as the others, but the failure is induced
in the *transport*, not the database. `PresenceHarness.FailStreamAfter(n)` arms a
test-only fault flag the service honors — it emits `n` `PresenceStatus` messages
then aborts the stream with an `RpcException`. The correct `PresenceClient`
consumes to clean end-of-stream and turns a mid-stream `RpcException` into 502
`presence:incomplete` (no partial list); the naive variant
(`tests/Naive/NaivePresenceClient`) swallows the `RpcException` in a try/catch and
returns whatever arrived. The expect-failure wrapper (`NaiveDemo.ExpectCatchToFail`)
runs S-PR-04's assertions against the naive-wired client and confirms they go red
(it returns a 2-member 200 instead of 502). The fault flag is a no-op in
production (never armed) and is cleared by the per-test reset; the service's code
path is identical with or without it.

---

## Hero cases (narrated in guide §4)

### G-IDOR — DM read without participant check (PostgreSQL / DMs)

- **Where it hides:** `src/messages` — the DM conversation/message read path.
- **Correct:** `GET /dm/conversations/{id}` and `…/{id}/messages` return 404
  to any non-participant; the participant predicate is applied in the request
  path. AC: S-DM-08, S-DM-09, S-DM-10.
- **Naive variant:** the handler loads the conversation/messages **by id
  only** — `isParticipant` exists, is correct, and is **never called** in this
  route ("correct logic, missing wiring" — the Tea/McHire shape).
- **Lying test (§3 lift #2 — "stubbed authorization guard"):** mocks the
  guard/predicate to `true` (or stubs the repository), then "verifies" the
  happy path returns the messages. Green against the naive variant: the
  security decision is switched off inside the test.
- **Catching test:** two real users, real DB: B requests A–C's conversation →
  expect 404 with the existence-hiding body. Red against the naive variant
  (200 + messages leak).
- **Class:** emergent — the property "only participants can read" exists only
  in the assembled route (middleware + handler + query scoping).
- **Honesty note:** `isParticipant` itself is a pure predicate — a unit test
  for it is correct and cheaper. The unit test just cannot know the predicate
  is wired in; that is the system's property, not the unit's.

### G-BOLA — channel authorization not wired (PostgreSQL / channels)

Two sub-cases, one root shape (correct rule, missing wiring):

**G-BOLA-READ** — private channel readable without membership.
- **Where:** `src/channels` — channel metadata/messages read path.
- **Correct:** private + non-member → 404 for metadata AND messages. AC:
  S-CH-05, S-CH-21.
- **Naive variant:** the read path checks only that the channel exists;
  `private` never consulted for the caller.
- **Lying test:** stubs the membership repository to return a membership (or
  mocks the channel as public), asserts messages come back. Green by
  construction.
- **Catching test:** real private channel, non-member B → metadata 404,
  messages 404. Red against naive (200).
- **Class:** emergent.

**G-BOLA-ROLE** — admin action without the role check.
- **Where:** `src/channels` — member management (add to private channel,
  kick, delete channel).
- **Correct:** plain `member` performing add/kick/delete → 403
  (visible-but-forbidden). AC: S-CH-11, S-CH-15, S-CH-19.
- **Naive variant:** membership is checked (caller is a member) but **role is
  not** — `hasRole(atLeast: admin)` exists, unused on this route.
- **Lying test:** unit test calls the service with a hand-built "admin"
  context object — the test constructs the very authority it should verify.
- **Catching test:** real channel, real `member` caller kicks another member
  → expect 403. Red against naive (204, member kicked).
- **Class:** emergent.
- **Honesty note (both):** the role-comparison predicate (`owner > admin >
  member`) is pure logic — unit-test it. The 404-vs-403 split and the wiring
  are system properties.

### G-CACHE — stale membership cache after removal (Redis)

- **Where:** `src/channels` — membership write path + the cached
  authorization fast path.
- **Correct:** kick/leave/add invalidates `members:{channelId}`; a removed
  member's next read is denied (404 private / 403 public) **immediately**.
  AC: S-CH-16, S-FD-06.
- **Naive variant:** the removal handler updates PostgreSQL and **forgets the
  cache invalidation** — the removed user keeps reading until TTL (300 s)
  expires.
- **Lying test:** mocks the cache (in-memory dict that the test itself keeps
  consistent with the DB write) — a mock cannot diverge from the DB, so the
  divergence bug is unrepresentable in it.
- **Catching test:** real Redis + real PG: B member reads messages (warms the
  cache), owner kicks B, B reads again → expect 404. Red against naive (200
  from stale cache).
- **Class:** emergent — cache coherence is a property of two stores plus the
  write path; it does not exist in any unit.
- **Honesty note:** TTL math or key-formatting helpers are unit-testable;
  coherence is not.

### G-RABBIT — notification double-delivery on redelivery (RabbitMQ)

- **Where:** `src/notifications` — the queue worker.
- **Correct:** exactly one `notifications` row per DM message under
  redelivery (unique on `dm_message_id` + duplicate treated as success →
  ack); poison job → DLQ after 3 attempts, queue keeps flowing. AC: S-NT-02,
  S-NT-03, S-NT-04.
- **Naive variant (insert-or-crash):** the worker inserts unconditionally and
  acks on success — it never *handles* the duplicate. On redelivery the
  insert hits the UNIQUE constraint, the worker crashes and nack-requeues,
  and after the delivery limit the duplicate job lands in the DLQ as if it
  were poison. *(Spec fix 2026-06-11, .NET pilot: the earlier "duplicate
  rows" phrasing contradicted `03-schema.md` — the UNIQUE(dm_message_id)
  backstop makes a literal double row impossible. The observable failure of
  the naive shape is therefore the dead-lettered duplicate + the crash-loop,
  not a second row.)*
- **Lying test (broker mocked):** asserts the producer "published exactly
  once" (`verify(publish).once()`); delivery semantics — the actual at-least-
  once redelivery — never execute against a mock.
- **Catching test:** real broker; harness forces redelivery of the same job
  (publish duplicate / nack-requeue); await the queue to settle → assert
  exactly **one** notification row AND an **empty DLQ** (the redelivered
  duplicate was treated as success, not dead-lettered). Red against naive
  (duplicate job in the DLQ after 3 crashed attempts). Companion DLQ test:
  poison job → DLQ depth 1 after 3 attempts, main queue empty, subsequent
  jobs flow.
- **Class:** emergent — idempotency under at-least-once delivery exists only
  with a real broker's redelivery semantics.
- **Honesty note:** the preview-truncation (first 100 chars) is a pure
  function — unit it.

### G-RACE — concurrent DM create yields duplicate conversations (in-system)

- **Where:** `src/messages` — DM conversation create.
- **Correct:** N concurrent `POST /dm/conversations` for the same pair → one
  row, every response 200/201 with the SAME conversation id. AC: S-DM-05.
- **Naive variant:** check-then-insert without the unique constraint's
  conflict handling (naive repository creates the table-level check in code:
  `if not exists → insert`) — the TOCTOU window.
- **Lying test:** single-threaded unit test of the service: "creating twice
  returns the same conversation" — sequential calls can never open the window.
- **Catching test:** fire K (≥ 8) truly concurrent requests through the real
  HTTP boundary against the real DB; assert exactly one `dm_conversations`
  row and all responses share one id. Red against naive (duplicate rows /
  500s). *Determinism is empirical — pilot-gate item (plan): the zero-flake
  gate decides K and whether the window opens reliably; the naive variant may
  additionally widen the window via a test-only hook if the pilot shows it is
  needed — documented here when proven.*
- **Class:** pure-logic-adjacent but **emergent in practice** — the race
  exists only under real concurrency through the full stack.
- **Honesty note:** pair normalization (`lo < hi`) is a pure function — unit
  it. Concurrency safety is not unit-testable.

### G-TX — partial commit on DM creation (in-system)

- **Where:** `src/messages` — DM create (conversation + 2 participant rows).
- **Correct:** the three inserts commit atomically; if any fails, no row
  survives. AC: S-DM-06.
- **Naive variant:** three sequential saves, no transaction — the agent shape
  "call repo.save three times."
- **Lying test (§3 lift #3 — "verify-the-call, not the outcome"):**
  `verify(repo.saveConversation).calledWith(…)` and
  `verify(repo.saveParticipant).calledTwice()` — asserts the calls happened,
  not that a consistent state exists; the partial-commit bug survives green.
- **Catching test:** harness installs a one-shot SQL trigger that raises on
  the **second** `dm_participants` insert; call the API → expect 500; then
  DB-state assert: **zero** conversation rows, zero participant rows. Red
  against naive (orphan conversation + one participant row persist).
- **Class:** emergent — atomicity is a property of the unit-of-work wiring,
  invisible to mocks by construction.
- **Honesty note:** nothing here is unit territory except input validation;
  the case is the cleanest "mock erases the seam" exhibit.

---

## Dependency-tour cases (narrated in guide §7; ship in repo)

### G-KAFKA — fire-and-forget event publish (Kafka)

- **Where:** `src/channels` — message post → `message.posted` publish.
- **Correct:** producer awaits broker confirmation; broker down → 503 and the
  message is NOT persisted; fan-out is eventually consistent and idempotent.
  AC: S-FD-01, S-FD-02, S-FD-05.
- **Naive variant (two shapes, both injectable):**
  - *(producer)* fire-and-forget publish (result ignored / not awaited);
    broker down → 201, message persisted, event silently lost, feeds never
    update — caught by S-FD-01;
  - *(consumer)* non-idempotent fan-out: unconditional feed insert +
    unconditional counter increment; event redelivery → duplicate feed entry
    and/or counter diverging from feed — caught by S-FD-05.
- **Lying test:** mocked producer always succeeds instantly; the test asserts
  feed consistency that the mock itself fabricated (in-process synchronous
  "bus").
- **Catching test:** harness **stops the Kafka container**; post a message →
  expect 503 and no `channel_messages` row (S-FD-01). Red against
  naive-producer (201 + silent loss). Redelivery probe: harness re-publishes
  the same event → exactly one feed entry, counter still 1 (S-FD-05) — red
  against naive-consumer. Companion positive test: broker up, post →
  await-until feed entries for all members except sender (S-FD-02 — this is
  the await-shape exhibit; it stays green against both impls and exists to
  contrast with the mock's fabricated instant consistency).
- **Class:** emergent — delivery semantics exist only against a real broker.
- **Honesty note:** payload serialization is unit-testable.

### G-S3 — attachment fetchable without channel authorization (S3/MinIO)

- **Where:** `src/attachments` — download path.
- **Correct:** `GET /attachments/{id}` resolves the attachment's channel and
  requires the caller's membership; otherwise 404. AC: S-AT-06, S-AT-07.
- **Naive variant:** handler looks up `storage_key` by id and streams the
  bytes — possession of the id IS access; membership never consulted.
- **Lying test:** mocks the storage client to return bytes and asserts the
  handler returns bytes — the authorization dimension is absent from the
  test's universe.
- **Catching test:** real MinIO + real DB: attachment in a private channel;
  non-member B fetches by id → 404, no bytes. Red against naive (200 +
  bytes leak).
- **Class:** emergent (same family as G-IDOR, different dependency shape —
  the object store makes "bytes by key" the tempting naive path).
- **Honesty note:** filename sanitization / size accounting are unit-friendly.

### G-LLM — unvalidated input into the prompt / unvalidated output (LLM)

- **Where:** `src/assistant` — summary feature.
- **Correct:** instructions only in the system prompt; messages passed as
  delimited data; model output validated (non-empty, ≤ 2000 chars) else 502.
  AC: S-SM-03, S-SM-04, S-SM-05.
- **Naive variant (two beats):** (a) concatenates raw message text into the
  instruction prompt ("Summarize this conversation: " + text…) — a message
  containing "ignore previous instructions…" becomes instructions; (b)
  returns `model.complete(...)` straight to the client, unvalidated.
- **Lying test:** mocks the LLM client to return "a summary" and asserts the
  endpoint returns it — prompt construction and output validation are both
  outside the test's universe.
- **Catching test:** the **fake with interaction verification** (`LlmHarness`)
  — (a) seed a channel with a hostile message; request summary; assert the
  captured request keeps instructions and data separated (hostile text
  appears ONLY inside a delimited data block); red against naive-(a). (b)
  program the fake to return a 5000-char / empty response; expect 502; red
  against naive-(b) (200 with garbage).
- **Class:** boundary case — this is where the guide teaches that the RIGHT
  fake (owned, interaction-verifying) differs from a reflex mock (the lying
  test's). Not "real dependency" — deliberately.
- **Honesty note:** the delimiting/escaping function is pure — unit it. The
  fact that the route USES it is not.

### G-HTTP — unfurl failure escapes / no circuit breaker (outbound HTTP)

- **Where:** `src/channels` — link-preview flow on message post.
- **Correct:** 800 ms timeout; timeout/5xx → message posts with null preview;
  breaker opens after 5 consecutive failures for 30 s. AC: S-LP-02, S-LP-03,
  S-LP-04.
- **Naive variant:** awaits the unfurl call with no timeout/no try-catch —
  a slow or 500ing unfurl service makes message posting hang or 500.
- **Lying test:** mocks the HTTP client to return 200 instantly — timeouts,
  sockets, and failure modes cannot occur in the mock's universe.
- **Catching test:** real stub server (`UnfurlHarness`): program a delay >
  timeout → post message → expect 201 within the deadline with null preview.
  Red against naive (request exceeds deadline / 500). Breaker test: program 5
  failures, post 6 messages → assert stub received exactly 5 requests.
- **Class:** emergent — resilience properties live in the real client config
  + socket behavior.
- **Honesty note:** the breaker's state machine (closed→open→half-open) is
  pure logic — unit-testing it is correct AND cheaper; the component test
  proves it is wired into the real client path. Both tests ship; the pairing
  is the §4 rubric in miniature.

### G-GRPC — partial stream treated as complete (gRPC)

- **Where:** `src/presence` — the API's consumption of
  `StreamChannelPresence`.
- **Correct:** consume to clean end-of-stream; mid-stream error → 502
  `presence:incomplete`. AC: S-PR-03, S-PR-04.
- **Naive variant:** collects messages in a try/catch that swallows the
  stream error and returns whatever arrived — a partial member list presented
  as complete.
- **Lying test:** mocks the gRPC client to return a fully-materialized list —
  streaming (and its failure midway) does not exist in the mock.
- **Catching test:** real presence service with the harness fault flag "fail
  after 2 messages": request channel presence (5 members) → expect 502, not a
  2-member 200. Red against naive (200 with 2 members).
- **Class:** emergent — stream semantics are a transport property; also the
  proof the harness boundary is transport-agnostic (design §2.17).
- **Honesty note:** presence-status mapping is unit-friendly.

---

## §3 gaming exhibits (lifted lying tests — design §11.H)

The §3 code blocks are NOT new artifacts; they are the three most vivid lying
tests from above, plus the weakened-validation case:

| §3 block | Source |
|---|---|
| 1. Tautological mock | G-TAUT below (DM message read — repo stubbed, assertion mirrors the stub) |
| 2. Stubbed authorization guard | G-IDOR's lying test |
| 3. Verify-the-call, not the outcome | G-TX's lying test |
| 4. Weakened validation (axis-1 gaming) | G-WEAKVAL below |

### G-TAUT — the tautological mock (exhibit-only case)

- **Where:** `src/messages` — DM message list.
- **Lying test (§3 lift #1):** stub the message repository to return a canned
  message; assert the service returns that message. Green by construction —
  it verifies the stub, not the system ("the mirror" in its purest form).
- **Catching counterpart:** the ordinary green component tests for S-DM-11
  (list messages through real HTTP + real DB) — no naive variant needed; this
  exhibit exists to be lifted into §3, paired in the README with the real
  test of the same behavior.
- **Class:** axis-1 exhibit (the mirror), kept distinct from the axis-2
  wrong-level cases per §11.H's honesty guardrail.

### G-WEAKVAL — weakened validation (axis-1 gaming)

- **Where:** `src/` pagination validation (shared), pinned by AC S-PG-01…04.
- **Correct:** `limit` 1–100, 422 beyond — strict, no clamping (locked).
- **The gaming story (narrated in §3):** the agent, facing a red test after
  widening a bound (or wanting `limit=50000` to pass), **rewrites the pinning
  test** instead of the code — axis-1: the test is changed to mirror the
  implementation. The repo exhibit: the pinning component tests themselves +
  a lying variant showing the after state (assertion weakened to whatever the
  implementation returns).
- **Catching mechanism:** the pinning tests + the §8 lock (tests are
  pre-existing and locked; the agent may not edit them — enforcement recipe
  in `scripts/lock-tests.sh`).
- **Class:** pure-logic bound + process failure; the case bridges §3 → §8.
- **Honesty note:** validation rules are EXACTLY where unit tests shine
  (cheap, exhaustive: 0/1/100/101/non-integer). The catching insight is not
  the level — it is who owns the assertion.

---

## Case index (summary)

| ID | Dependency / kind | Guide home | Hero? | Catching ACs |
|---|---|---|---|---|
| G-IDOR | PostgreSQL / DMs | §4 | ✓ | S-DM-08,09,10 |
| G-BOLA-READ | PostgreSQL / channels | §4 | ✓ | S-CH-05,21 |
| G-BOLA-ROLE | PostgreSQL / channels | §4 | ✓ | S-CH-11,15,19 |
| G-CACHE | Redis | §4 | ✓ | S-CH-16, S-FD-06 |
| G-RABBIT | RabbitMQ | §4 | ✓ | S-NT-02,03,04 |
| G-RACE | in-system | §4 | ✓ | S-DM-05 |
| G-TX | in-system | §4 | ✓ | S-DM-06 |
| G-KAFKA | Kafka | §7 | | S-FD-01,05 (S-FD-02 = await-shape exhibit) |
| G-S3 | S3/MinIO | §7 | | S-AT-06,07 |
| G-LLM | LLM (fake) | §7 | | S-SM-03,04,05 |
| G-HTTP | outbound HTTP | §7 | | S-LP-02,03,04 |
| G-GRPC | gRPC | §7 | | S-PR-04 (S-PR-03 = happy counterpart) |
| G-TAUT | exhibit | §3 | | S-DM-11 (counterpart) |
| G-WEAKVAL | validation/process | §3→§8 | | S-PG-01…04 |

Every gallery catching scenario is **MANDATORY in every language** (plan:
non-negotiable; happy paths are the only trim knob). Every lying test follows
§0.2 and appears in the README paired with its catcher.
