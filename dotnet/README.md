# Relay — .NET (origin reference)

**Stack (locked):** ASP.NET Core, net10.0, EF Core + PostgreSQL, MediatR,
FluentValidation, xUnit + FluentAssertions, Testcontainers, Respawn. This is the
**origin reference** — the harness pattern adapted from drim.dev's suite, with
`IHarness<T>` renamed to **`IDependencyHarness<T>`** (locked, design §11.J).

- `src/Relay.Api/` — the app, vertical slices by domain. Ships **CORRECT**.
- `harness/Relay.Testing/` — the reusable dependency bricks, one per dependency
  (`IDependencyHarness<T>`, `DatabaseHarness`, `RedisHarness`, `KafkaHarness`,
  `RabbitMqHarness`, `S3Harness`, `LlmHarness`, `UnfurlHarness`, `PresenceHarness`,
  `HttpClientHarness`).
- `tests/Relay.Api.Tests/` — component tests through the HTTP boundary against
  real dependencies. Each gallery case is a **pair**: a green-but-useless lying
  test (`tests/Lying/`, `*LyingTests`) + the catching component test, plus a
  naive-variant red→green demonstration (`tests/Naive/` + `NaiveDemo`).

## One-command test run

```bash
dotnet test Relay.slnx      # from this dotnet/ directory
```

Requires Docker: on first run Testcontainers pulls PostgreSQL, Redis, Kafka,
RabbitMQ and MinIO (all digest-pinned). No CI by design — verification is this
suite: clone and run it.

## Status (Task 2.1 — COMPLETE)

**The full dependency set is implemented and GREEN.** Suite: **145 tests, 0
failures, 0 warnings** under `TreatWarningsAsErrors` + `latest-recommended`
analyzers. All 83 acceptance scenarios (`spec/06-acceptance.md`) are covered 1:1:
S-ID, S-US, S-PG, S-DM, S-CH, S-AT, S-NT, S-FD, S-LP, S-PR, S-SM.

**All eight dependencies** ship as harnesses in `harness/Relay.Testing/`:
PostgreSQL (`DatabaseHarness`), Redis (`RedisHarness`), Kafka (`KafkaHarness`),
RabbitMQ (`RabbitMqHarness`), S3/MinIO (`S3Harness`), the LLM fake (`LlmHarness`),
the outbound-HTTP stub (`UnfurlHarness`), and the real in-process gRPC presence
service (`PresenceHarness`).

**All 14 gallery cases** ship as exhibits. The 12 injectable cases are each a
**triple** — a green-but-useless lying test (`tests/Lying/`), the catching
component test, and a naive-variant red→green demonstration (`tests/Naive/` +
`NaiveDemo.ExpectCatchToFail`): G-IDOR, G-BOLA-READ, G-BOLA-ROLE, G-CACHE,
G-RACE, G-TX, G-KAFKA, G-RABBIT, G-S3, G-LLM, G-HTTP, G-GRPC. G-TAUT (exhibit +
its ordinary S-DM-11 counterpart) and G-WEAKVAL (lying test + pinning tests)
round out the set.

**Broker zero-flake gate: PASSED — 50/50 consecutive runs** of the Kafka +
RabbitMQ scenarios, 0 failures (local gate; there is no CI).

**Container images:** all five pinned BY DIGEST in
`harness/Relay.Testing/ContainerImages.cs` (`image:tag@sha256:…`) — postgres
17-alpine, redis 7-alpine, apache/kafka 3.9.0, rabbitmq 4-management-alpine,
minio (latest-by-digest). The gRPC presence service runs in-process (no
container).
