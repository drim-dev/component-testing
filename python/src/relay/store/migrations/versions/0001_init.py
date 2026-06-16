"""Initial Relay schema — mirrors spec/03-schema.md exactly.

Constraints here ARE product behavior: the unique pair (RACE), the unique
notification on dm_message_id (RABBIT), the unique feed entry on (user, message)
(KAFKA). feed_entries.message_id carries NO foreign key on purpose
(03-schema.md): the post-message ordering is publish-confirmed-then-commit, so
the feed projection may momentarily lead channel_messages.

Revision ID: 0001_init
Revises:
Create Date: 2026-06-12
"""

from __future__ import annotations

from alembic import op

revision = "0001_init"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE users (
            id           text PRIMARY KEY,
            handle       text NOT NULL UNIQUE,
            display_name text NOT NULL,
            created_at   timestamptz NOT NULL
        );

        CREATE TABLE dm_conversations (
            id         text PRIMARY KEY,
            user_lo    text NOT NULL REFERENCES users(id),
            user_hi    text NOT NULL REFERENCES users(id),
            created_at timestamptz NOT NULL,
            CHECK (user_lo < user_hi),
            UNIQUE (user_lo, user_hi)
        );

        CREATE TABLE dm_participants (
            conversation_id text NOT NULL REFERENCES dm_conversations(id),
            user_id         text NOT NULL REFERENCES users(id),
            PRIMARY KEY (conversation_id, user_id)
        );

        CREATE TABLE channels (
            id         text PRIMARY KEY,
            name       text NOT NULL,
            private    boolean NOT NULL,
            created_at timestamptz NOT NULL
        );

        CREATE TABLE channel_members (
            channel_id text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
            user_id    text NOT NULL REFERENCES users(id),
            role       text NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
            joined_at  timestamptz NOT NULL,
            PRIMARY KEY (channel_id, user_id)
        );

        CREATE TABLE channel_messages (
            id                 text PRIMARY KEY,
            channel_id         text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
            sender_id          text NOT NULL REFERENCES users(id),
            text               text NOT NULL,
            link_preview_title text NULL,
            created_at         timestamptz NOT NULL
        );

        CREATE INDEX channel_messages_page ON channel_messages (channel_id, created_at DESC, id);

        CREATE TABLE dm_messages (
            id              text PRIMARY KEY,
            conversation_id text NOT NULL REFERENCES dm_conversations(id),
            sender_id       text NOT NULL REFERENCES users(id),
            text            text NOT NULL,
            created_at      timestamptz NOT NULL
        );

        CREATE INDEX dm_messages_page ON dm_messages (conversation_id, created_at DESC, id);

        CREATE TABLE attachments (
            id          text PRIMARY KEY,
            channel_id  text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
            uploader_id text NOT NULL REFERENCES users(id),
            message_id  text NULL REFERENCES channel_messages(id),
            filename    text NOT NULL,
            size_bytes  bigint NOT NULL,
            storage_key text NOT NULL UNIQUE,
            created_at  timestamptz NOT NULL
        );

        CREATE TABLE notifications (
            id              text PRIMARY KEY,
            user_id         text NOT NULL REFERENCES users(id),
            dm_message_id   text NOT NULL REFERENCES dm_messages(id) UNIQUE,
            conversation_id text NOT NULL REFERENCES dm_conversations(id),
            sender_id       text NOT NULL REFERENCES users(id),
            preview         text NOT NULL,
            created_at      timestamptz NOT NULL
        );

        CREATE INDEX notifications_page ON notifications (user_id, created_at DESC);

        CREATE TABLE feed_entries (
            id         text PRIMARY KEY,
            user_id    text NOT NULL REFERENCES users(id),
            channel_id text NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
            message_id text NOT NULL,
            sender_id  text NOT NULL REFERENCES users(id),
            preview    text NOT NULL,
            created_at timestamptz NOT NULL,
            UNIQUE (user_id, message_id)
        );

        CREATE INDEX feed_entries_page ON feed_entries (user_id, created_at DESC);
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DROP TABLE IF EXISTS feed_entries;
        DROP TABLE IF EXISTS notifications;
        DROP TABLE IF EXISTS attachments;
        DROP TABLE IF EXISTS dm_messages;
        DROP TABLE IF EXISTS channel_messages;
        DROP TABLE IF EXISTS channel_members;
        DROP TABLE IF EXISTS channels;
        DROP TABLE IF EXISTS dm_participants;
        DROP TABLE IF EXISTS dm_conversations;
        DROP TABLE IF EXISTS users;
        """
    )
