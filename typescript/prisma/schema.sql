-- Relay's canonical schema, byte-for-behavior identical to ../../spec/03-schema.md
-- and the other languages' migrations. Applied by the DatabaseHarness at suite
-- boot (Prisma Client is the typed query layer; this SQL owns the DDL so the
-- CHECK constraints, FKs, ON DELETE CASCADE, and the deliberately-absent
-- feed_entries.message_id FK are exactly the product behavior the gallery leans
-- on). The _tx_fault trigger is the deterministic G-TX fault probe.

CREATE TABLE IF NOT EXISTS users (
    id           text PRIMARY KEY,
    handle       text NOT NULL UNIQUE,
    display_name text NOT NULL,
    created_at   timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS dm_conversations (
    id         text PRIMARY KEY,
    user_lo    text NOT NULL REFERENCES users(id),
    user_hi    text NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL,
    CHECK (user_lo < user_hi),
    UNIQUE (user_lo, user_hi)
);

CREATE TABLE IF NOT EXISTS dm_participants (
    conversation_id text NOT NULL REFERENCES dm_conversations(id),
    user_id         text NOT NULL REFERENCES users(id),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE IF NOT EXISTS channels (
    id         text PRIMARY KEY,
    name       text NOT NULL,
    private    boolean NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS channel_members (
    channel_id text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id    text NOT NULL REFERENCES users(id),
    role       text NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    joined_at  timestamptz NOT NULL,
    PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE IF NOT EXISTS channel_messages (
    id                 text PRIMARY KEY,
    channel_id         text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    sender_id          text NOT NULL REFERENCES users(id),
    text               text NOT NULL,
    link_preview_title text NULL,
    created_at         timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS channel_messages_page ON channel_messages (channel_id, created_at DESC, id);

CREATE TABLE IF NOT EXISTS dm_messages (
    id              text PRIMARY KEY,
    conversation_id text NOT NULL REFERENCES dm_conversations(id),
    sender_id       text NOT NULL REFERENCES users(id),
    text            text NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS dm_messages_page ON dm_messages (conversation_id, created_at DESC, id);

CREATE TABLE IF NOT EXISTS attachments (
    id          text PRIMARY KEY,
    channel_id  text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    uploader_id text NOT NULL REFERENCES users(id),
    message_id  text NULL REFERENCES channel_messages(id),
    filename    text NOT NULL,
    size_bytes  bigint NOT NULL,
    storage_key text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
    id              text PRIMARY KEY,
    user_id         text NOT NULL REFERENCES users(id),
    dm_message_id   text NOT NULL REFERENCES dm_messages(id) UNIQUE,
    conversation_id text NOT NULL REFERENCES dm_conversations(id),
    sender_id       text NOT NULL REFERENCES users(id),
    preview         text NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS notifications_page ON notifications (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS feed_entries (
    id         text PRIMARY KEY,
    user_id    text NOT NULL REFERENCES users(id),
    channel_id text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    message_id text NOT NULL,
    sender_id  text NOT NULL REFERENCES users(id),
    preview    text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (user_id, message_id)
);

CREATE INDEX IF NOT EXISTS feed_entries_page ON feed_entries (user_id, created_at DESC);

-- G-TX deterministic fault: ArmParticipantInsertFault sets remaining=2, and the
-- trigger raises on the SECOND dm_participants insert of a transaction. The
-- correct transactional writer rolls everything back; the naive one leaves an
-- orphan. Cleared by the per-test reset.
CREATE TABLE IF NOT EXISTS _tx_fault (id int PRIMARY KEY, remaining int NOT NULL);

CREATE OR REPLACE FUNCTION _tx_fault_raise() RETURNS trigger AS $$
DECLARE left_count int;
BEGIN
    UPDATE _tx_fault SET remaining = remaining - 1 WHERE id = 1 RETURNING remaining INTO left_count;
    IF left_count IS NOT NULL AND left_count = 0 THEN
        RAISE EXCEPTION 'tx fault injected on participant insert';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS _tx_fault_trg ON dm_participants;
CREATE TRIGGER _tx_fault_trg BEFORE INSERT ON dm_participants
    FOR EACH ROW EXECUTE FUNCTION _tx_fault_raise();
