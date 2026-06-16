# Relay — Java

**Stack (locked):** Spring Boot + JPA/Flyway + JUnit5 + Testcontainers,
Gradle (Kotlin DSL). The Gradle wrapper is generated at Task 3.2 start
(locked decision).

- `src/` — the app by domain; ships CORRECT
- `harness/` — DependencyHarness classes per dependency
- `tests/` — 83 scenarios per `../spec/06-acceptance.md`; lying tests named
  `*LyingTest`

## One-command test run

```bash
./gradlew test      # from this java/ directory
```

Requires Docker: on first run Testcontainers pulls PostgreSQL, Redis, Kafka,
RabbitMQ and MinIO (all digest-pinned). No CI by design — verification is this
suite: clone and run it. Suite: **115 tests, 0 failures**.
