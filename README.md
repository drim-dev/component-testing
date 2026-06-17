# Relay — companion repository for «Карта верификации»

> **The verification map.** Every gallery case below → where the bug would hide
> in `src/`, its **lying test** (green-but-useless exhibit), its **catching
> test** (red against a naive variant, green against the correct app), and the
> guide section that narrates it. Each link is a permanent permalink pinned to
> commit `f66a2da9103ff66c15afa01ecc8f11eb4da46ae7`.

One product — **Relay**, a minimal messaging + community service with an AI
summary assistant — implemented five times: `dotnet/`, `go/`, `typescript/`,
`java/`, `python/`. The same behavior, the same database schema, the same
**83-scenario acceptance catalog** (`spec/06-acceptance.md`), proven by
component tests against the API's **real dependencies** (PostgreSQL, Redis,
Kafka, RabbitMQ, MinIO), a verifying fake for the LLM, a fault-injecting stub
for outbound HTTP, and a **stubbed gRPC presence neighbour** (a real gRPC socket
backed by canned answers — a neighbour service is stubbed, not run for real).

## Don't trust a badge — run it

This repository deliberately ships **no CI**. The claim "the pattern works" is
not a green badge; it is a test suite you can run yourself:

| Language | One-command test run |
|---|---|
| .NET | `cd dotnet && dotnet test Relay.slnx` |
| Go | `cd go && go test ./...` |
| TypeScript | `cd typescript && npm test` |
| Java | `cd java && ./gradlew test` |
| Python | `cd python && pytest` |

Requirements: Docker (Testcontainers). Container images are pinned by digest;
this repo is a frozen teaching exhibit — read it for concepts, not version
numbers.

## The verification map (gallery index)

Fourteen cases. The reference column links the **.NET** sources (the pilot;
the other four languages mirror them case-for-case — same scenario ids, same
naive shapes). Every link is pinned to `f66a2da9103ff66c15afa01ecc8f11eb4da46ae7` and is permanent. Lying tests
carry `lying` in the path (`tests/.../Lying/…`); catching tests live under
`tests/.../Features/…` and run red against the naive variant injected for that
one test. Full case records — correct behavior, naive variant, honesty note —
are in [`spec/05-gallery.md`](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/spec/05-gallery.md).

### Hero cases — narrated in guide §4

| Case | What it is | Where it hides | Lying test (exhibit) | Catching test | Guide §  |
|---|---|---|---|---|---|
| **G-IDOR** | DM read without a participant check | `src/messages` — DM conversation/message read path | [`MessagesLyingTests` · IDOR](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/MessagesLyingTests.cs#L16-L30) | [`DmReadTests` · S-DM-08](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Messages/DmReadTests.cs#L45-L60) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx), [§1](../../frontend/src/content/guides/testing/verifying-agent-code/sections/01-green-no-longer-means-done.mdx) |
| **G-BOLA-READ** | Private channel readable without membership | `src/channels` — channel metadata/messages read path | [`ChannelsLyingTests` · BOLA-READ](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/ChannelsLyingTests.cs#L14-L25) | [`ChannelReadTests` · S-CH-05](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Channels/ChannelReadTests.cs#L62-L68) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |
| **G-BOLA-ROLE** | Admin action without the role check | `src/channels` — member management | [`ChannelsLyingTests` · BOLA-ROLE](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/ChannelsLyingTests.cs#L27-L35) | [`ChannelMembershipTests` · S-CH-15](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Channels/ChannelMembershipTests.cs#L191-L209) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |
| **G-CACHE** | Stale membership cache after removal | `src/channels` — membership write + cached fast path | [`CacheLyingTests` · CACHE](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/CacheLyingTests.cs#L15-L32) | [`ChannelCacheTests` · S-CH-16](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Channels/ChannelCacheTests.cs#L16-L31) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |
| **G-RABBIT** | Notification double-delivery on redelivery | `src/notifications` — the queue worker | [`NotificationsLyingTests` · RABBIT](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/NotificationsLyingTests.cs#L14-L28) | [`NotificationTests` · S-NT-02](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Notifications/NotificationTests.cs#L56-L69) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |
| **G-RACE** | Concurrent DM create → duplicate conversations | `src/messages` — DM conversation create | [`MessagesLyingTests` · RACE](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/MessagesLyingTests.cs#L32-L44) | [`CreateConversationTests` · S-DM-05](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Messages/CreateConversationTests.cs#L90-L97) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |
| **G-TX** | Partial commit on DM creation | `src/messages` — DM create (conversation + 2 participants) | [`MessagesLyingTests` · TX](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/MessagesLyingTests.cs#L46-L56) | [`CreateConversationTests` · S-DM-06](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Messages/CreateConversationTests.cs#L122-L129) | [§4](../../frontend/src/content/guides/testing/verifying-agent-code/sections/04-what-to-test-at-what-level.mdx) |

### Dependency-tour cases — narrated in guide §7

| Case | What it is | Where it hides | Lying test (exhibit) | Catching test | Guide §  |
|---|---|---|---|---|---|
| **G-KAFKA** | Fire-and-forget event publish | `src/channels` — `message.posted` publish + feed fan-out | [`FeedLyingTests` · KAFKA](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/FeedLyingTests.cs#L14-L30) | [`FeedFanoutTests` · S-FD-01](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Feed/FeedFanoutTests.cs#L25-L38) | [§7](../../frontend/src/content/guides/testing/verifying-agent-code/sections/07-real-dependencies.mdx) |
| **G-S3** | Attachment fetchable without channel authorization | `src/attachments` — download path | [`AttachmentsLyingTests` · S3](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/AttachmentsLyingTests.cs#L17-L42) | [`AttachmentTests` · S-AT-06](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Attachments/AttachmentTests.cs#L111-L116) | [§7](../../frontend/src/content/guides/testing/verifying-agent-code/sections/07-real-dependencies.mdx) |
| **G-LLM** | Unvalidated input into / output from the prompt | `src/assistant` — summary feature | [`AssistantLyingTests` · LLM](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/AssistantLyingTests.cs#L15-L30) | [`SummaryTests` · S-SM-03](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Assistant/SummaryTests.cs#L66-L71) | [§7](../../frontend/src/content/guides/testing/verifying-agent-code/sections/07-real-dependencies.mdx) |
| **G-HTTP** | Unfurl failure escapes / no circuit breaker | `src/channels` — link-preview flow on message post | [`LinkPreviewLyingTests` · HTTP](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/LinkPreviewLyingTests.cs#L15-L29) | [`LinkPreviewTests` · S-LP-02](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Channels/LinkPreviewTests.cs#L39-L44) | [§7](../../frontend/src/content/guides/testing/verifying-agent-code/sections/07-real-dependencies.mdx) |
| **G-GRPC** | Partial stream treated as complete | `src/presence` — consumption of `StreamChannelPresence` | [`PresenceLyingTests` · GRPC](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/PresenceLyingTests.cs#L15-L32) | [`PresenceTests` · S-PR-04](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Presence/PresenceTests.cs#L79-L84) | [§7](../../frontend/src/content/guides/testing/verifying-agent-code/sections/07-real-dependencies.mdx) |

### §3 gaming exhibits — narrated in guide §3

These two are not new artifacts: they are the most vivid mirrors lifted into §3.
G-TAUT has no naive variant — its counterpart is the ordinary green component
test of the same behavior. G-WEAKVAL is the axis-1 process failure (the agent
rewrites a pinning test instead of the code); its catcher is the pre-existing
pinning test plus the §8 lock recipe in [`scripts/lock-tests.sh`](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/scripts/lock-tests.sh).

| Case | What it is | Where it hides | Lying test (exhibit) | Catching counterpart | Guide §  |
|---|---|---|---|---|---|
| **G-TAUT** | The tautological mock (mirror in its purest form) | `src/messages` — DM message list | [`MessagesLyingTests` · TAUT](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/MessagesLyingTests.cs#L58-L71) | [`DmMessageTests` · S-DM-11](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Messages/DmMessageTests.cs#L44-L66) | [§3](../../frontend/src/content/guides/testing/verifying-agent-code/sections/03-two-axes.mdx) |
| **G-WEAKVAL** | Weakened validation (axis-1 gaming) | `src/` shared pagination validation | [`ChannelsLyingTests` · WEAKVAL](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Lying/ChannelsLyingTests.cs#L37-L43) | [`ChannelReadTests` · S-PG-01..03 (pin)](https://github.com/drim-dev/component-testing/blob/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/tests/Relay.Api.Tests/Features/Channels/ChannelReadTests.cs#L107-L120) | [§3](../../frontend/src/content/guides/testing/verifying-agent-code/sections/03-two-axes.mdx) → [§8](../../frontend/src/content/guides/testing/verifying-agent-code/sections/08-agent-in-the-loop.mdx) |

The harness machinery these tests share — the five reusable bricks and their
composition — is dissected in [§5](../../frontend/src/content/guides/testing/verifying-agent-code/sections/05-harness-five-bricks.mdx)
(.NET) and [§6](../../frontend/src/content/guides/testing/verifying-agent-code/sections/06-one-pattern-five-idioms.mdx)
(the same pattern in five idioms), reading from [`dotnet/harness/Relay.Testing/`](https://github.com/drim-dev/component-testing/tree/f66a2da9103ff66c15afa01ecc8f11eb4da46ae7/dotnet/harness/Relay.Testing).

## Layout (identical per language)

```
<lang>/
  src/      — the app, organized by domain (messages, channels, attachments,
              notifications, assistant, presence). Ships CORRECT. No labels,
              no signposting — bugs are demonstrated in tests only.
  harness/  — the reusable dependency harnesses (DependencyHarness per dep)
  tests/    — the gallery: per case, a LYING test (green-but-useless exhibit,
              named *lying*) paired with the component test that catches the
              bug live (red against a naive variant injected only inside that
              test).
spec/       — the language-agnostic single source of truth (domain, API,
              schema, dependencies, bug gallery, acceptance catalog)
scripts/    — lock-tests.sh (the citable test-lock recipe; not wired to run here)
```

## The spec is the law

If an implementation disagrees with `spec/`, that is a spec bug — the spec is
fixed first, then the code. Every test name embeds its scenario id
(`S-DM-08`, …) so conformance is mechanically checkable across languages.
