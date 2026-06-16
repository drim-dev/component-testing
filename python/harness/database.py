"""The Postgres harness (DatabaseHarness).

Start = container + Alembic migration once. Seed = write through real
constraints (via the Store). Assert = direct queries for DB-state checks (e.g.
"no orphan conversation row" in TX). Reset = TRUNCATE every table (the fast
per-language reset idiom; contrast Redis FLUSHDB). Fault injection = a one-shot
trigger raising on the 2nd dm_participants insert (the G-TX probe).
"""

from __future__ import annotations

import os
import subprocess
import sys

from sqlalchemy import text
from testcontainers.postgres import PostgresContainer

from harness.images import POSTGRES_IMAGE

_TABLES = [
    "feed_entries",
    "notifications",
    "attachments",
    "dm_messages",
    "channel_messages",
    "channel_members",
    "channels",
    "dm_participants",
    "dm_conversations",
    "users",
]

# The G-TX fault: a one-shot trigger that raises on the SECOND dm_participants
# insert. A row in relay_fault_state arms it; the trigger disarms itself by
# counting inserts so exactly the second one fails, then it is cleared per reset.
_FAULT_DDL = """
CREATE TABLE IF NOT EXISTS relay_participant_fault (armed boolean NOT NULL, seen int NOT NULL);

CREATE OR REPLACE FUNCTION relay_participant_fault_fn() RETURNS trigger AS $$
DECLARE
    state relay_participant_fault%ROWTYPE;
BEGIN
    SELECT * INTO state FROM relay_participant_fault LIMIT 1;
    IF state.armed THEN
        UPDATE relay_participant_fault SET seen = seen + 1;
        IF state.seen + 1 >= 2 THEN
            RAISE EXCEPTION 'relay test fault: second dm_participants insert';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS relay_participant_fault_trg ON dm_participants;
CREATE TRIGGER relay_participant_fault_trg
    BEFORE INSERT ON dm_participants
    FOR EACH ROW EXECUTE FUNCTION relay_participant_fault_fn();
"""


class DatabaseHarness:
    def __init__(self) -> None:
        self._container: PostgresContainer | None = None
        self._dsn = ""

    def start(self) -> None:
        self._container = PostgresContainer(
            image=POSTGRES_IMAGE, username="relay", password="relay", dbname="relay"
        )
        self._container.start()
        self._dsn = self._container.get_connection_url().replace(
            "postgresql+psycopg2://", "postgresql+psycopg://"
        )
        self._migrate()
        self._install_fault()

    @property
    def dsn(self) -> str:
        return self._dsn

    def _migrate(self) -> None:
        env = dict(os.environ)
        env["RELAY_DATABASE_URL"] = self._dsn
        result = subprocess.run(
            [sys.executable, "-m", "alembic", "upgrade", "head"],
            cwd=_project_root(),
            env=env,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(f"alembic upgrade failed:\n{result.stdout}\n{result.stderr}")

    def _install_fault(self) -> None:
        from sqlalchemy import create_engine

        engine = create_engine(self._dsn, future=True)
        with engine.begin() as conn:
            conn.execute(text(_FAULT_DDL))
            conn.execute(text("DELETE FROM relay_participant_fault"))
            conn.execute(
                text("INSERT INTO relay_participant_fault (armed, seen) VALUES (false, 0)")
            )
        engine.dispose()

    def arm_participant_insert_fault(self, store_engine) -> None:
        """Arm the G-TX fault so the next conversation create's 2nd participant
        insert raises mid-transaction."""
        with store_engine.begin() as conn:
            conn.execute(text("UPDATE relay_participant_fault SET armed = true, seen = 0"))

    def reset(self, store_engine) -> None:
        with store_engine.begin() as conn:
            conn.execute(text("UPDATE relay_participant_fault SET armed = false, seen = 0"))
            conn.execute(
                text("TRUNCATE " + ", ".join(_TABLES) + " RESTART IDENTITY CASCADE")
            )

    def stop(self) -> None:
        if self._container is not None:
            self._container.stop()


def _project_root() -> str:
    # harness/ -> python/
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
