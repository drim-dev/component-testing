# Relay — Python

**Stack (locked):** FastAPI + SQLAlchemy + Alembic + pytest +
testcontainers-python. The real↔fake seam swap is FastAPI's
`dependency_overrides`.

- `src/` — the app by domain; ships CORRECT
- `harness/` — DependencyHarness classes per dependency
- `tests/` — 83 scenarios per `../spec/06-acceptance.md`; lying tests named
  `test_lying_*.py`

## One-command test run

```bash
python -m venv .venv && source .venv/bin/activate   # first time only
pip install -r requirements.txt                     # first time only
pytest      # from this python/ directory
```

Requires Docker: on first run testcontainers-python pulls PostgreSQL, Redis,
Kafka, RabbitMQ and MinIO (all digest-pinned). No CI by design — verification is
this suite: clone and run it. Suite: **111 tests, 0 failures**.
