# Relay — Go

**Stack (locked):** chi + pgx + sqlc + golang-migrate + testcontainers-go.
**No DI framework** — the seam is constructor + interface, which is the
strongest stress test of the pattern's universality (and of the naive-variant
injection mechanic).

- `src/` — the app by domain; ships CORRECT
- `harness/` — `DependencyHarness` interface + concrete structs
  (`KafkaHarness`, `RedisHarness`, … — no `I` prefix, locked)
- `tests/` — 83 scenarios per `../spec/06-acceptance.md`; lying tests named
  `*_lying_test.go`

## One-command test run

```bash
go test ./...      # from this go/ directory
```

Requires Docker: on first run testcontainers-go pulls PostgreSQL, Redis, Kafka,
RabbitMQ and MinIO (all digest-pinned). No CI by design — verification is this
suite: clone and run it. Suite: **117 tests, 0 failures** (`tests/` package).
