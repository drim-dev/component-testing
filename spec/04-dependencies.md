# 04 — Dependency Contracts & Harness Shapes

**Status:** Phase-0 spec. The eight dependencies are **frozen** (design §2.17 /
§11.A). Each earns its place by having a **distinct harness shape**. This file
pins, per dependency: its role, its observable contract, and the shape of its
`DependencyHarness`.

## 0. Naming (locked — design §11.J)

- Central abstraction: **`DependencyHarness`** — never bare `Harness` (which
  collides with "test harness = the whole rig").
- .NET: `IDependencyHarness<T>` (generic where meaningful) with concretes
  `DatabaseHarness`, `RedisHarness`, `KafkaHarness`, `RabbitMqHarness`,
  `S3Harness`, `LlmHarness`, `UnfurlHarness`, `PresenceHarness`.
- Go: interface `DependencyHarness` (no `I` prefix) + concrete structs
  (`KafkaHarness`, …).
- TS / Java / Python: idiomatic equivalents, same concrete names.
- Prose short name (Russian): «харнесс» — introduced once with a Russian-led
  gloss, then used freely (locked).

## 0.1 The harness shape (the five bricks, per dependency)

Every `DependencyHarness` answers the same five questions; HOW it answers them
is the per-dependency shape:

1. **Start** — bring up the real dependency (Testcontainers) or construct the
   fake; expose connection config to the app under test.
2. **Wire the seam** — how the app's composition is pointed at it (per-language
   idiom: .NET `RemoveAll`+re-register, Nest DI override, Spring test config,
   Go constructor argument, FastAPI `dependency_overrides`).
3. **Seed** — put the dependency into a known state.
4. **Assert** — read the dependency's state for verification (incl. async
   await-until where the dependency is eventually consistent).
5. **Reset** — return to a clean state between tests, fast.

## 0.2 Real-vs-fake boundary (locked)

| Real (containerized) | Faked / stubbed |
|---|---|
| PostgreSQL, Redis, Kafka, RabbitMQ, S3 (MinIO) | LLM (in-process fake with interaction verification) |
| gRPC presence service (real companion-owned process, in-test) | Outbound HTTP unfurl (local **stub server** with fault injection — a controlled real socket, not an in-process mock) |

Rule taught by the guide: **containerizable → real; nondeterministic / paid /
external-third-party → fake at a deliberate boundary.**

## 0.3 Container images (locked: pinned by digest)

Every Testcontainers image reference MUST be pinned by **digest**
(`postgres:17-alpine@sha256:…`), not by floating tag. Digests recorded by the
.NET pilot 2026-06-11; all languages use these exact digests.

| Dependency | Image (digest pinned at pilot) |
|---|---|
| PostgreSQL | `postgres:17-alpine@sha256:979c4379dd698aba0b890599a6104e082035f98ef31d9b9291ec22f2b13059ca` |
| Redis | `redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99` |
| Kafka | `apache/kafka:3.9.0@sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f` (KRaft, no ZooKeeper) |
| RabbitMQ | `rabbitmq:4-management-alpine@sha256:96827325bdd90cb6feecd35bd9e37276876359a092570550edc58ce234273c15` |
| S3 | `minio/minio:latest@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` (digest freezes "latest") |

---

## 1. PostgreSQL — sync relational store

- **Role:** system of record (all tables in `03-schema.md`).
- **Contract:** migrations applied at boot; constraints are product behavior
  (unique pair, unique notification, unique feed entry).
- **Harness shape (`DatabaseHarness`):** start container → run migrations once;
  `Seed(entities…)` writes through real constraints; `Assert` = direct queries
  for DB-state assertions (e.g., "no orphan conversation row" in TX);
  **Reset = the per-language fast-reset idiom** (Respawn / TRUNCATE / rollback
  / template DB) — the deliberately divergent brick (§6).
- **Fault injection (used by TX catching test):** the harness can install a
  one-shot SQL trigger that raises on a chosen insert (e.g., second
  `dm_participants` row) to force a mid-transaction failure deterministically.
  *Pilot-gate item: validate this mechanism is portable and honest in .NET+Go.*

## 2. Redis — sync KV / cache

- **Role:** membership cache (`members:{channelId}`, TTL 300 s), unread
  counters (`unread:{userId}:{channelId}`), unfurl circuit-breaker state
  (`unfurl:breaker:*`).
- **Contract:** cache is **invalidated on membership writes** (kick/leave/add);
  counters incremented exactly once per consumed event; reset by
  `POST /channels/{id}/read`.
- **Harness shape (`RedisHarness`):** start container; `Seed` = set keys
  directly (e.g., pre-warm a membership cache to prove invalidation);
  `Assert` = read keys/TTLs; **Reset = `FLUSHDB`** (the trivially fast reset —
  contrast with PG).

## 3. Kafka — async event log

- **Role:** `message.posted` events (topic `message-posted`, key = channelId,
  JSON payload `{ messageId, channelId, senderId, preview, postedAt }`).
  Consumer group `feed-fanout` writes feed entries + increments unread.
- **Contract:** producer **awaits broker confirmation** before the API
  responds 201; broker unavailable → 503 and nothing persisted (no
  fire-and-forget — gallery KAFKA). Pinned ordering: insert →
  publish-confirmed → commit (`02-api.md` §3 — no outbox, accepted window).
  Consumer is idempotent per (user, message) — unique constraint + counter
  increment only on first successful insert.
- **Harness shape (`KafkaHarness`):** start container (single-node KRaft);
  `Seed` = produce a crafted event directly (test the consumer in isolation
  from the producer); `Assert` = **await-until** (poll consume with deadline,
  default 10 s; never `sleep`) — the canonical eventual-consistency assertion
  the guide's §7 deep-dives; also "assert NOT delivered within window" for
  negative cases (bounded, flagged as the one acceptable bounded-wait);
  `Reset` = delete/recreate topics (or unique per-test topic prefix — pilot
  decides the flake-free idiom); **fault control:** pause/stop the broker
  container to make "broker down" deterministic.

## 4. RabbitMQ — queues / acks / DLQ

- **Role:** DM notification jobs. Queue `notify.dm` (quorum), consumer writes
  `notifications` rows; **DLQ `notify.dm.dlq`** via dead-letter exchange after
  **3** delivery attempts.
- **Contract:** worker is idempotent on `dm_message_id` (unique constraint +
  treat duplicate as success → ack). Poison job (e.g., recipient id that
  cannot be resolved) → after 3 attempts lands in DLQ, queue keeps flowing.
- **Harness shape (`RabbitMqHarness`):** start container; `Seed` = publish a
  job directly (incl. a poison job, incl. **forcing redelivery** by publishing
  a duplicate / nack-requeue — the deterministic double-delivery probe);
  `Assert` = await-until on DB effect + queue inspection (depth of `notify.dm`
  and the DLQ via management API or passive declare); `Reset` = purge queues.
- Second broker on purpose: log/offset semantics (Kafka) vs queue/ack/DLQ
  semantics (Rabbit) — different shapes, different bugs.

## 5. S3 (MinIO) — object storage over HTTP

- **Role:** attachment bytes, bucket `relay-attachments`, key = `storage_key`.
- **Contract:** bytes are written before the attachment row commits; download
  authorization derives from channel membership (NEVER from key possession).
- **Harness shape (`S3Harness`):** start MinIO container; create bucket at
  boot; `Seed` = put objects directly; `Assert` = get/list objects, compare
  bytes; `Reset` = delete all objects (bucket survives).
- Teaching point carried by this dep: the **container-vs-fake decision** — S3
  *could* be faked in-process, but MinIO is cheap and real HTTP+auth+streaming
  is where bugs live.

## 6. LLM — the canonical FAKE

- **Role:** channel summary (`POST /channels/{id}/summary`).
- **Why fake (the boundary anchor):** nondeterministic, paid, external. The
  fake is a deliberate architectural decision, not a reflex mock — this is the
  guide's real-vs-fake centerpiece (§11.F).
- **App-side port:** a single narrow interface (`SummaryModel` /
  `ISummaryModel`): input = system prompt + list of delimited message blocks;
  output = summary text. The app NEVER builds a prompt string inline in a
  handler — the port boundary is where the fake attaches.
- **Harness shape (`LlmHarness`):** in-process fake implementing the port;
  `Seed` = program the next response (canned summary, empty string, 5000-char
  overflow — output-contract violations); `Assert` = **interaction
  verification**: capture the exact request — system prompt constant, message
  content present as delimited data blocks, instruction text free of raw user
  content (the injection catch); `Reset` = clear programmed responses +
  captured calls.
- The fake must be a hand-rolled test double in each language (no Moq-style
  framework magic required) so the pattern reads cross-language.

## 7. Outbound HTTP — unfurl stub + fault injection

- **Role:** link unfurl (`GET /unfurl?url=…` → `{ "title" }`) with 800 ms
  timeout, graceful degradation, and a 5-failure/30 s circuit breaker
  (`02-api.md` §6).
- **Harness shape (`UnfurlHarness`):** a real local **stub HTTP server**
  (WireMock / MSW-node / httptest / respx-style local server — per-language
  idiom), NOT an in-process mock of the client class: the timeout, the socket,
  and the status codes must be real. `Seed` = program routes: 200+title,
  fixed delay > timeout, 500, connection reset; `Assert` = received-request
  count (circuit-breaker proof) + request URLs; `Reset` = clear programmed
  routes + counters.
- This is the general case of which the LLM fake is the special case — the
  guide says so explicitly (§7).

## 8. gRPC — thin internal presence service

- **Role:** presence. Companion-owned service, same proto in all languages:

  ```proto
  syntax = "proto3";
  package relay.presence.v1;

  service Presence {
    rpc GetPresence(GetPresenceRequest) returns (PresenceStatus);          // unary
    rpc StreamChannelPresence(StreamChannelPresenceRequest)
        returns (stream PresenceStatus);                                   // server-streaming
  }

  message GetPresenceRequest { string user_id = 1; }
  message StreamChannelPresenceRequest { repeated string user_ids = 1; }
  message PresenceStatus { string user_id = 1; bool online = 2; }
  ```

- **Contract:** heartbeat sets `presence:{userId}` in Redis with 60 s TTL; the
  stream emits exactly one `PresenceStatus` per requested user then closes OK.
  The API client must consume to clean stream end; mid-stream error → API 502
  (`presence:incomplete`), never a partial list as complete.
- **Harness shape (`PresenceHarness`):** start the real companion presence
  service in-test (in-process server or child process — per-language idiom)
  on an ephemeral port; `Seed` = set presence state; **fault control:** make
  the stream fail after N messages (the service honors a test-only fault flag
  or the harness fronts it) — the deterministic partial-stream probe;
  `Assert` = via API responses + the service's Redis; `Reset` = clear state +
  fault flags.
- **Scope guard:** gRPC stays *thin* (one unary + one streaming RPC). It is
  the first dependency to defer if the pilot shows bloat (plan, still open).

---

## 9. Cross-cutting harness rules

- **No `sleep`-based assertions.** Async deps assert via await-until with a
  deadline (poll interval ≤ 100 ms, deadline 10 s default). The zero-flake
  gate (50 consecutive broker runs, locally) enforces this.
- **One Docker host, one suite at a time.** Suites must not assume exclusive
  fixed host ports — all container ports are dynamically mapped.
- **Reset covers every dependency** the test touched: PG tables, Redis
  FLUSHDB, Kafka topics, Rabbit queues, S3 objects, LLM fake state, stub
  routes, presence state.
- **The composition is fixed per suite** (a `TestFixture`-style composition of
  all harnesses); extensibility = add a harness class, not runtime
  re-composition (honest framing — design §2.3).
- Harness code lives in `<lang>/harness/`, reusable bricks only — no
  test-case logic.
