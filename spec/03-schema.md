# 03 — Database Schema

**Status:** Phase-0 spec. The schema is **identical in all five languages**;
each language uses its own migrator (EF Core / Flyway / golang-migrate /
Alembic / Prisma Migrate) but produces these exact tables, constraints, and
indexes. Column naming below is `snake_case`; ORMs map as needed.

Types are PostgreSQL. `id` columns are `text` primary keys (opaque ids,
generator per language — see `02-api.md` §0).

## Tables

### users
| column | type | constraints |
|---|---|---|
| id | text | PK |
| handle | text | NOT NULL, **UNIQUE** |
| display_name | text | NOT NULL |
| created_at | timestamptz | NOT NULL |

### dm_conversations
| column | type | constraints |
|---|---|---|
| id | text | PK |
| user_lo | text | NOT NULL, FK → users(id) |
| user_hi | text | NOT NULL, FK → users(id) |
| created_at | timestamptz | NOT NULL |

- **CHECK (user_lo < user_hi)** — the pair is stored normalized (lexicographic
  order of ids).
- **UNIQUE (user_lo, user_hi)** — at most one conversation per pair. This
  constraint is the backstop the RACE gallery case leans on: concurrent
  creates must resolve to one row (the application handles the unique
  violation by returning the existing conversation).

### dm_participants
| column | type | constraints |
|---|---|---|
| conversation_id | text | NOT NULL, FK → dm_conversations(id) |
| user_id | text | NOT NULL, FK → users(id) |

- **PK (conversation_id, user_id)**.
- Denormalizes the pair for the participant-check predicate and exists so DM
  creation is a **multi-row atomic write** (conversation + 2 participants) —
  the TX gallery case's surface. Implementations MUST write conversation and
  both participants in one transaction.

### channels
| column | type | constraints |
|---|---|---|
| id | text | PK |
| name | text | NOT NULL |
| private | boolean | NOT NULL |
| created_at | timestamptz | NOT NULL |

### channel_members
| column | type | constraints |
|---|---|---|
| channel_id | text | NOT NULL, FK → channels(id) ON DELETE CASCADE |
| user_id | text | NOT NULL, FK → users(id) |
| role | text | NOT NULL, CHECK (role IN ('owner','admin','member')) |
| joined_at | timestamptz | NOT NULL |

- **PK (channel_id, user_id)**.
- Exactly one `owner` row per channel — application invariant (no partial
  unique index required; enforced by the only paths that write `owner`:
  channel creation).

### channel_messages
| column | type | constraints |
|---|---|---|
| id | text | PK |
| channel_id | text | NOT NULL, FK → channels(id) ON DELETE CASCADE |
| sender_id | text | NOT NULL, FK → users(id) |
| text | text | NOT NULL |
| link_preview_title | text | NULL |
| created_at | timestamptz | NOT NULL |

- **INDEX (channel_id, created_at DESC, id)** — newest-first pagination.

### dm_messages
| column | type | constraints |
|---|---|---|
| id | text | PK |
| conversation_id | text | NOT NULL, FK → dm_conversations(id) |
| sender_id | text | NOT NULL, FK → users(id) |
| text | text | NOT NULL |
| created_at | timestamptz | NOT NULL |

- **INDEX (conversation_id, created_at DESC, id)**.

### attachments
| column | type | constraints |
|---|---|---|
| id | text | PK |
| channel_id | text | NOT NULL, FK → channels(id) ON DELETE CASCADE |
| uploader_id | text | NOT NULL, FK → users(id) |
| message_id | text | NULL, FK → channel_messages(id) |
| filename | text | NOT NULL |
| size_bytes | bigint | NOT NULL |
| storage_key | text | NOT NULL, UNIQUE |
| created_at | timestamptz | NOT NULL |

- `message_id` is set when the attachment is referenced by a message create;
  authorization NEVER reads `storage_key` possession — access is derived from
  `channel_id` membership (the S3 gallery case hides exactly there).

### notifications
| column | type | constraints |
|---|---|---|
| id | text | PK |
| user_id | text | NOT NULL, FK → users(id) |
| dm_message_id | text | NOT NULL, FK → dm_messages(id), **UNIQUE** |
| conversation_id | text | NOT NULL, FK → dm_conversations(id) |
| sender_id | text | NOT NULL, FK → users(id) |
| preview | text | NOT NULL |
| created_at | timestamptz | NOT NULL |

- **UNIQUE (dm_message_id)** — the idempotency anchor: under RabbitMQ
  redelivery, the worker must produce exactly one row (RABBIT gallery case).
  The unique constraint is the backstop; the worker must also treat a
  duplicate as success (ack, not requeue).
- **INDEX (user_id, created_at DESC)**.

### feed_entries
| column | type | constraints |
|---|---|---|
| id | text | PK |
| user_id | text | NOT NULL, FK → users(id) |
| channel_id | text | NOT NULL, FK → channels(id) ON DELETE CASCADE |
| message_id | text | NOT NULL — **deliberately NO FK** (see note) |
| sender_id | text | NOT NULL, FK → users(id) |
| preview | text | NOT NULL |
| created_at | timestamptz | NOT NULL |

- **UNIQUE (user_id, message_id)** — Kafka consumer idempotency under event
  redelivery; the Redis unread increment must be coupled to a *successful
  first* insert (counters must not diverge from feed — see `05-gallery.md`).
- **INDEX (user_id, created_at DESC)**.
- **No FK on `message_id` (pinned):** the post-message ordering is
  publish-confirmed-then-commit (`02-api.md` §3), so the feed projection may
  momentarily lead `channel_messages`; an FK would make the consumer race a
  not-yet-committed row. The feed is a projection — its integrity anchor is
  the UNIQUE pair, not an FK. Feed rows for a deleted channel are removed via
  the `channel_id` FK cascade.

## Not in PostgreSQL

- **Unread counters** — Redis: key `unread:{userId}:{channelId}` (integer).
- **Membership cache** — Redis: key `members:{channelId}` (set of user ids),
  TTL 300 s, **invalidated on membership writes** (CACHE gallery case).
- **Unfurl circuit-breaker state** — Redis: `unfurl:breaker:failures`,
  `unfurl:breaker:open_until` (see `02-api.md` §6).
- **Presence** — Redis (inside the presence service): key
  `presence:{userId}`, TTL 60 s.
- **Attachment bytes** — S3 bucket `relay-attachments`, object key =
  `storage_key`.

## Migration & reset notes (for harness design)

- Migrations run once per test-suite start (container boot), not per test.
- Per-test reset must clear **all** tables above plus Redis (`FLUSHDB`) and
  the S3 bucket; the reset strategy is per-language idiom (Respawn / TRUNCATE
  / transaction rollback / template DB) — divergence here is a teaching
  feature (§6 of the guide).
- Seeding helpers must write through the same constraints (no constraint
  bypassing), so seeded states are reachable product states.
