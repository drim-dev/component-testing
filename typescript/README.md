# Relay — TypeScript

**Stack (locked):** NestJS + Prisma + Vitest + testcontainers-node. The
real↔fake seam swap rides Nest's DI container.

- `src/` — the app by domain; ships CORRECT
- `harness/` — `DependencyHarness` classes per dependency + the `Fixture`
  composition + `naiveApp` (the single-provider DI override)
- `tests/` — 83 scenarios per `../spec/06-acceptance.md` (1:1, the id is in
  each test name); gallery exhibits under `tests/gallery/`; naive variants under
  `tests/naive/`

## Running the suite

```bash
npm test          # vitest run — requires Docker (Testcontainers)
```

The whole suite shares ONE Testcontainers composition (Postgres, Redis, Kafka,
RabbitMQ, MinIO, plus the in-process LLM fake, the local unfurl stub, and the
companion-owned gRPC presence service). Vitest runs serial in a single fork
with `isolate: false`, so the `Fixture` singleton boots the containers exactly
once and each test resets the dependencies between scenarios (never sleeps —
await-until on broker offsets / queue stats).

## The naive-variant injection seam (Nest idiom)

Each injectable gallery case hides behind a narrow DI token the handlers depend
on (`DM_ACCESS`, `CHANNEL_READ_GATE`, …). The CORRECT implementation is
registered in `AppModule.build(infra)`. A demo derives a SECOND Relay host from
the same live containers with `Test.createTestingModule({ imports:
[AppModule.build(infra)] }).overrideProvider(TOKEN).useValue(naive)` — exactly
one provider swapped, nothing else changed — and runs on a distinct IdGen
generator id (1) so its ids never collide with seeded data. `expectCatchToFail`
runs the catching test's OWN assertion block against that naive host and asserts
it goes RED, keeping a red demonstration runnable inside a green suite. The
worker-side cases (G-RABBIT, G-KAFKA consumer) swap the seam in a naive consumer
host pointed at the parallel topic/queue so it never races the suite's correct
consumer.

## How Kafka starts (digest preserved)

`harness/kafka.harness.ts` does NOT use a Testcontainers Kafka module (those
detect the image to pick a starter script, which a digest pin defeats). Instead a
`GenericContainer` runs the **digest-pinned** `apache/kafka:3.9.0@sha256:…`
single-node KRaft broker; the entrypoint blocks on a starter script copied in
once the host port is mapped, which rewrites `KAFKA_ADVERTISED_LISTENERS` to the
real port and execs the image's own `/etc/kafka/docker/run` — the Go-pilot
technique. Broker-down probes pause/unpause the container; the harness blocks for
broker-ready + a stable consumer group before returning, so the next test never
races the rejoin.

## Gallery case index (lying test ↔ catcher)

Every lying test (`tests/gallery/gallery.lying.spec.ts`, `*.lying.spec.ts`,
§0.2) is real, green, and useless — it verifies a mock, not the system. Each is
paired here with its catching test (the green scenario of the same id) and, for
injectable cases, its naive red→green demo (`tests/gallery/naive-demos.spec.ts`).

| Case | Lying test | Catching test(s) | Naive demo |
|---|---|---|---|
| G-IDOR | `gallery.lying` G-IDOR | S-DM-08/09/10 (`dm.spec.ts`) | ✓ |
| G-BOLA-READ | `gallery.lying` G-BOLA-READ | S-CH-05/21 (`channels.spec.ts`) | ✓ |
| G-BOLA-ROLE | `gallery.lying` G-BOLA-ROLE | S-CH-11/15/19 (`channels.spec.ts`) | ✓ |
| G-CACHE | `gallery.lying` G-CACHE | S-CH-16 (`channels.spec.ts`), S-FD-06 (`feed.spec.ts`) | ✓ |
| G-RABBIT | `gallery.lying` G-RABBIT | S-NT-02/03/04 (`notifications.spec.ts`) | ✓ |
| G-RACE | `gallery.lying` G-RACE | S-DM-05 (`dm.spec.ts`) | ✓ |
| G-TX | `gallery.lying` G-TX | S-DM-06 (`dm.spec.ts`) | ✓ |
| G-KAFKA | `gallery.lying` G-KAFKA | S-FD-01/05 (`feed.spec.ts`) | ✓ (producer + consumer) |
| G-S3 | `gallery.lying` G-S3 | S-AT-06/07 (`attachments.spec.ts`) | ✓ |
| G-LLM | `gallery.lying` G-LLM | S-SM-03/04/05 (`summary.spec.ts`) | ✓ |
| G-HTTP | `gallery.lying` G-HTTP | S-LP-02/03/04 (`linkpreview.spec.ts`) | ✓ |
| G-GRPC | `gallery.lying` G-GRPC | S-PR-04 (`presence.spec.ts`) | ✓ |
| G-TAUT | `gallery.lying` G-TAUT | S-DM-11 (`dm.spec.ts`) | — (exhibit only) |
| G-WEAKVAL | `gallery.lying` G-WEAKVAL | S-PG-01…04 (`pagination.spec.ts`) | — (process/§8 lock) |
