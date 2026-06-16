"""Relay's PostgreSQL data layer over SQLAlchemy Core + psycopg.

Queries are hand-written ``text()`` SQL run through a SQLAlchemy Engine (the
ORM mapping would obscure the constraints, which here ARE product behavior). The
unique pair (RACE), unique notification (RABBIT), and unique feed entry (KAFKA)
are the backstops the gallery leans on.

The connection pool is deliberately SMALL: under the test suite many
``dependency_overrides`` app-instances share one Postgres container, so an
unbounded pool exhausts Postgres' ``max_connections`` (the Java handoff's
learning, ported here).
"""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from datetime import datetime

from sqlalchemy import Connection, create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from relay import domain


def is_unique_violation(err: Exception) -> bool:
    """Whether err is a Postgres unique-constraint violation (SQLSTATE 23505) —
    the duplicate-treated-as-success path for the RABBIT and KAFKA consumers."""
    if isinstance(err, IntegrityError):
        orig = err.orig
        sqlstate = getattr(orig, "sqlstate", None) or getattr(orig, "pgcode", None)
        return sqlstate == "23505"
    return False


class Store:
    """Wraps a SQLAlchemy Engine. Typed methods for every query Relay needs.

    Methods that participate in a larger transaction (the channel-message write,
    the atomic conversation create) accept a ``Connection``; the rest open and
    commit their own short transaction via the engine.
    """

    def __init__(self, dsn: str, pool_size: int = 3) -> None:
        # SQLAlchemy 2.0 wants the psycopg v3 driver named explicitly.
        url = _sqlalchemy_url(dsn)
        self._engine: Engine = create_engine(
            url, pool_size=pool_size, max_overflow=2, pool_pre_ping=True, future=True
        )

    @property
    def engine(self) -> Engine:
        return self._engine

    def close(self) -> None:
        self._engine.dispose()

    @contextmanager
    def begin(self) -> Iterator[Connection]:
        """A transaction the caller drives (post-message ordering, atomic create)."""
        with self._engine.begin() as conn:
            yield conn

    # ---- Users ----

    def insert_user(self, user: domain.User) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO users (id, handle, display_name, created_at) "
                    "VALUES (:id, :handle, :display_name, :created_at)"
                ),
                _user_params(user),
            )

    def user_by_id(self, user_id: str) -> domain.User | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text("SELECT id, handle, display_name, created_at FROM users WHERE id = :id"),
                {"id": user_id},
            ).first()
        return _user(row) if row else None

    def user_by_handle(self, handle: str) -> domain.User | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text("SELECT id, handle, display_name, created_at FROM users WHERE handle = :h"),
                {"h": handle},
            ).first()
        return _user(row) if row else None

    # ---- Conversations & DM messages ----

    def conversation_by_id(self, conversation_id: str) -> domain.Conversation | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT id, user_lo, user_hi, created_at FROM dm_conversations WHERE id = :id"
                ),
                {"id": conversation_id},
            ).first()
        return _conversation(row) if row else None

    def conversation_by_pair(self, lo: str, hi: str) -> domain.Conversation | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT id, user_lo, user_hi, created_at FROM dm_conversations "
                    "WHERE user_lo = :lo AND user_hi = :hi"
                ),
                {"lo": lo, "hi": hi},
            ).first()
        return _conversation(row) if row else None

    def conversations_for(self, user_id: str, before: str, limit: int) -> list[domain.Conversation]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT c.id, c.user_lo, c.user_hi, c.created_at "
                    "FROM dm_conversations c "
                    "JOIN dm_participants p ON p.conversation_id = c.id AND p.user_id = :uid "
                    "WHERE (:before = '' OR c.id < :before) "
                    "ORDER BY c.id DESC LIMIT :lim"
                ),
                {"uid": user_id, "before": before, "lim": limit},
            ).all()
        return [_conversation(r) for r in rows]

    def conversation_exists(self, conversation_id: str) -> bool:
        with self._engine.connect() as conn:
            return bool(
                conn.execute(
                    text("SELECT EXISTS(SELECT 1 FROM dm_conversations WHERE id = :id)"),
                    {"id": conversation_id},
                ).scalar()
            )

    def insert_dm_message(self, message: domain.DmMessage) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO dm_messages (id, conversation_id, sender_id, text, created_at) "
                    "VALUES (:id, :cid, :sid, :text, :created_at)"
                ),
                {
                    "id": message.id,
                    "cid": message.conversation_id,
                    "sid": message.sender_id,
                    "text": message.text,
                    "created_at": message.created_at,
                },
            )

    def dm_messages(self, conversation_id: str, before: str, limit: int) -> list[domain.DmMessage]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT id, conversation_id, sender_id, text, created_at FROM dm_messages "
                    "WHERE conversation_id = :cid AND (:before = '' OR id < :before) "
                    "ORDER BY id DESC LIMIT :lim"
                ),
                {"cid": conversation_id, "before": before, "lim": limit},
            ).all()
        return [_dm_message(r) for r in rows]

    def dm_message_exists(self, conversation_id: str, message_id: str) -> bool:
        with self._engine.connect() as conn:
            return bool(
                conn.execute(
                    text(
                        "SELECT EXISTS(SELECT 1 FROM dm_messages "
                        "WHERE conversation_id = :cid AND id = :id)"
                    ),
                    {"cid": conversation_id, "id": message_id},
                ).scalar()
            )

    # ---- Channels & members ----

    def insert_channel_with_owner(
        self, channel: domain.Channel, owner: domain.ChannelMember
    ) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO channels (id, name, private, created_at) "
                    "VALUES (:id, :name, :private, :created_at)"
                ),
                {
                    "id": channel.id,
                    "name": channel.name,
                    "private": channel.private,
                    "created_at": channel.created_at,
                },
            )
            conn.execute(_INSERT_MEMBER, _member_params(owner))

    def channel_by_id(self, channel_id: str) -> domain.Channel | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text("SELECT id, name, private, created_at FROM channels WHERE id = :id"),
                {"id": channel_id},
            ).first()
        return _channel(row) if row else None

    def membership(self, channel_id: str, user_id: str) -> domain.ChannelMember | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT channel_id, user_id, role, joined_at FROM channel_members "
                    "WHERE channel_id = :cid AND user_id = :uid"
                ),
                {"cid": channel_id, "uid": user_id},
            ).first()
        return _member(row) if row else None

    def member_count(self, channel_id: str) -> int:
        with self._engine.connect() as conn:
            return int(
                conn.execute(
                    text("SELECT COUNT(*) FROM channel_members WHERE channel_id = :cid"),
                    {"cid": channel_id},
                ).scalar_one()
            )

    def member_ids_except(self, channel_id: str, except_user: str) -> list[str]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT user_id FROM channel_members "
                    "WHERE channel_id = :cid AND user_id <> :ex"
                ),
                {"cid": channel_id, "ex": except_user},
            ).all()
        return [r[0] for r in rows]

    def member_ids(self, channel_id: str) -> list[str]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text("SELECT user_id FROM channel_members WHERE channel_id = :cid"),
                {"cid": channel_id},
            ).all()
        return [r[0] for r in rows]

    def insert_member(self, member: domain.ChannelMember) -> None:
        with self._engine.begin() as conn:
            conn.execute(_INSERT_MEMBER, _member_params(member))

    def update_member_role(self, channel_id: str, user_id: str, role: domain.Role) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE channel_members SET role = :role "
                    "WHERE channel_id = :cid AND user_id = :uid"
                ),
                {"cid": channel_id, "uid": user_id, "role": role.label},
            )

    def delete_member(self, channel_id: str, user_id: str) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text("DELETE FROM channel_members WHERE channel_id = :cid AND user_id = :uid"),
                {"cid": channel_id, "uid": user_id},
            )

    def delete_channel(self, channel_id: str) -> None:
        with self._engine.begin() as conn:
            conn.execute(text("DELETE FROM channels WHERE id = :id"), {"id": channel_id})

    def visible_channels(self, user_id: str, before: str, limit: int) -> list[domain.Channel]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT DISTINCT c.id, c.name, c.private, c.created_at FROM channels c "
                    "LEFT JOIN channel_members m ON m.channel_id = c.id AND m.user_id = :uid "
                    "WHERE (c.private = false OR m.user_id IS NOT NULL) "
                    "AND (:before = '' OR c.id < :before) "
                    "ORDER BY c.id DESC LIMIT :lim"
                ),
                {"uid": user_id, "before": before, "lim": limit},
            ).all()
        return [_channel(r) for r in rows]

    def channel_exists(self, channel_id: str) -> bool:
        with self._engine.connect() as conn:
            return bool(
                conn.execute(
                    text("SELECT EXISTS(SELECT 1 FROM channels WHERE id = :id)"),
                    {"id": channel_id},
                ).scalar()
            )

    # ---- Channel messages (write participates in the post-message transaction) ----

    def insert_channel_message(self, conn: Connection, message: domain.ChannelMessage) -> None:
        conn.execute(
            text(
                "INSERT INTO channel_messages "
                "(id, channel_id, sender_id, text, link_preview_title, created_at) "
                "VALUES (:id, :cid, :sid, :text, :title, :created_at)"
            ),
            {
                "id": message.id,
                "cid": message.channel_id,
                "sid": message.sender_id,
                "text": message.text,
                "title": message.link_preview_title,
                "created_at": message.created_at,
            },
        )

    def channel_messages(
        self, channel_id: str, before: str, limit: int
    ) -> list[domain.ChannelMessage]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT id, channel_id, sender_id, text, link_preview_title, created_at "
                    "FROM channel_messages "
                    "WHERE channel_id = :cid AND (:before = '' OR id < :before) "
                    "ORDER BY id DESC LIMIT :lim"
                ),
                {"cid": channel_id, "before": before, "lim": limit},
            ).all()
        return [_channel_message(r) for r in rows]

    def channel_message_exists(self, channel_id: str, message_id: str) -> bool:
        with self._engine.connect() as conn:
            return bool(
                conn.execute(
                    text(
                        "SELECT EXISTS(SELECT 1 FROM channel_messages "
                        "WHERE channel_id = :cid AND id = :id)"
                    ),
                    {"cid": channel_id, "id": message_id},
                ).scalar()
            )

    def attach_message_to_attachments(
        self, conn: Connection, message_id: str, attachment_ids: Sequence[str]
    ) -> None:
        if not attachment_ids:
            return
        conn.execute(
            text("UPDATE attachments SET message_id = :mid WHERE id = ANY(:ids)"),
            {"mid": message_id, "ids": list(attachment_ids)},
        )

    # ---- Attachments ----

    def insert_attachment(self, attachment: domain.Attachment) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO attachments "
                    "(id, channel_id, uploader_id, message_id, filename, size_bytes, "
                    "storage_key, created_at) "
                    "VALUES (:id, :cid, :uid, :mid, :fn, :size, :key, :created_at)"
                ),
                {
                    "id": attachment.id,
                    "cid": attachment.channel_id,
                    "uid": attachment.uploader_id,
                    "mid": attachment.message_id,
                    "fn": attachment.filename,
                    "size": attachment.size_bytes,
                    "key": attachment.storage_key,
                    "created_at": attachment.created_at,
                },
            )

    def attachment_by_id(self, attachment_id: str) -> domain.Attachment | None:
        with self._engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT id, channel_id, uploader_id, message_id, filename, size_bytes, "
                    "storage_key, created_at FROM attachments WHERE id = :id"
                ),
                {"id": attachment_id},
            ).first()
        return _attachment(row) if row else None

    def attachments_owned_in_channel(
        self, channel_id: str, uploader_id: str, ids: Sequence[str]
    ) -> list[str]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT id FROM attachments "
                    "WHERE channel_id = :cid AND uploader_id = :uid AND id = ANY(:ids)"
                ),
                {"cid": channel_id, "uid": uploader_id, "ids": list(ids)},
            ).all()
        return [r[0] for r in rows]

    # ---- Notifications ----

    def insert_notification(self, notification: domain.Notification) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO notifications "
                    "(id, user_id, dm_message_id, conversation_id, sender_id, preview, created_at) "
                    "VALUES (:id, :uid, :dmid, :cid, :sid, :preview, :created_at)"
                ),
                {
                    "id": notification.id,
                    "uid": notification.user_id,
                    "dmid": notification.dm_message_id,
                    "cid": notification.conversation_id,
                    "sid": notification.sender_id,
                    "preview": notification.preview,
                    "created_at": notification.created_at,
                },
            )

    def notifications_for(
        self, user_id: str, before: str, limit: int
    ) -> list[domain.Notification]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT id, user_id, dm_message_id, conversation_id, sender_id, preview, "
                    "created_at FROM notifications "
                    "WHERE user_id = :uid AND (:before = '' OR id < :before) "
                    "ORDER BY id DESC LIMIT :lim"
                ),
                {"uid": user_id, "before": before, "lim": limit},
            ).all()
        return [_notification(r) for r in rows]

    def count_notifications_for_message(self, dm_message_id: str) -> int:
        with self._engine.connect() as conn:
            return int(
                conn.execute(
                    text("SELECT COUNT(*) FROM notifications WHERE dm_message_id = :id"),
                    {"id": dm_message_id},
                ).scalar_one()
            )

    # ---- Feed ----

    def insert_feed_entry(self, entry: domain.FeedEntry) -> None:
        with self._engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO feed_entries "
                    "(id, user_id, channel_id, message_id, sender_id, preview, created_at) "
                    "VALUES (:id, :uid, :cid, :mid, :sid, :preview, :created_at)"
                ),
                {
                    "id": entry.id,
                    "uid": entry.user_id,
                    "cid": entry.channel_id,
                    "mid": entry.message_id,
                    "sid": entry.sender_id,
                    "preview": entry.preview,
                    "created_at": entry.created_at,
                },
            )

    def feed_for(self, user_id: str, before: str, limit: int) -> list[domain.FeedEntry]:
        with self._engine.connect() as conn:
            rows = conn.execute(
                text(
                    "SELECT id, user_id, channel_id, message_id, sender_id, preview, created_at "
                    "FROM feed_entries "
                    "WHERE user_id = :uid AND (:before = '' OR id < :before) "
                    "ORDER BY id DESC LIMIT :lim"
                ),
                {"uid": user_id, "before": before, "lim": limit},
            ).all()
        return [_feed_entry(r) for r in rows]

    def count_feed_for_message(self, user_id: str, message_id: str) -> int:
        with self._engine.connect() as conn:
            return int(
                conn.execute(
                    text(
                        "SELECT COUNT(*) FROM feed_entries "
                        "WHERE user_id = :uid AND message_id = :mid"
                    ),
                    {"uid": user_id, "mid": message_id},
                ).scalar_one()
            )


_INSERT_MEMBER = text(
    "INSERT INTO channel_members (channel_id, user_id, role, joined_at) "
    "VALUES (:cid, :uid, :role, :joined_at)"
)


def _sqlalchemy_url(dsn: str) -> str:
    for prefix in ("postgresql+psycopg://", "postgres://", "postgresql://"):
        if dsn.startswith(prefix):
            rest = dsn[len(prefix):]
            return "postgresql+psycopg://" + rest
    return dsn


def _user_params(user: domain.User) -> dict[str, object]:
    return {
        "id": user.id,
        "handle": user.handle,
        "display_name": user.display_name,
        "created_at": user.created_at,
    }


def _member_params(member: domain.ChannelMember) -> dict[str, object]:
    return {
        "cid": member.channel_id,
        "uid": member.user_id,
        "role": member.role.label,
        "joined_at": member.joined_at,
    }


def _user(row) -> domain.User:
    return domain.User(id=row[0], handle=row[1], display_name=row[2], created_at=_utc(row[3]))


def _conversation(row) -> domain.Conversation:
    return domain.Conversation(id=row[0], user_lo=row[1], user_hi=row[2], created_at=_utc(row[3]))


def _dm_message(row) -> domain.DmMessage:
    return domain.DmMessage(
        id=row[0], conversation_id=row[1], sender_id=row[2], text=row[3], created_at=_utc(row[4])
    )


def _channel(row) -> domain.Channel:
    return domain.Channel(id=row[0], name=row[1], private=row[2], created_at=_utc(row[3]))


def _member(row) -> domain.ChannelMember:
    return domain.ChannelMember(
        channel_id=row[0], user_id=row[1], role=domain.Role.parse(row[2]), joined_at=_utc(row[3])
    )


def _channel_message(row) -> domain.ChannelMessage:
    return domain.ChannelMessage(
        id=row[0],
        channel_id=row[1],
        sender_id=row[2],
        text=row[3],
        link_preview_title=row[4],
        created_at=_utc(row[5]),
    )


def _attachment(row) -> domain.Attachment:
    return domain.Attachment(
        id=row[0],
        channel_id=row[1],
        uploader_id=row[2],
        message_id=row[3],
        filename=row[4],
        size_bytes=row[5],
        storage_key=row[6],
        created_at=_utc(row[7]),
    )


def _notification(row) -> domain.Notification:
    return domain.Notification(
        id=row[0],
        user_id=row[1],
        dm_message_id=row[2],
        conversation_id=row[3],
        sender_id=row[4],
        preview=row[5],
        created_at=_utc(row[6]),
    )


def _feed_entry(row) -> domain.FeedEntry:
    return domain.FeedEntry(
        id=row[0],
        user_id=row[1],
        channel_id=row[2],
        message_id=row[3],
        sender_id=row[4],
        preview=row[5],
        created_at=_utc(row[6]),
    )


def _utc(value: datetime) -> datetime:
    return value
