"""Alembic environment. Online-only (the harness always has a live container).

The DSN comes from ``RELAY_DATABASE_URL`` (the testcontainers Postgres), not the
ini placeholder — migrations are applied once per suite boot.
"""

from __future__ import annotations

import os

from alembic import context
from sqlalchemy import create_engine

config = context.config


def _url() -> str:
    url = os.environ.get("RELAY_DATABASE_URL")
    if not url:
        raise RuntimeError("RELAY_DATABASE_URL must be set for Alembic to run")
    for prefix in ("postgres://", "postgresql://"):
        if url.startswith(prefix) and not url.startswith("postgresql+psycopg://"):
            return "postgresql+psycopg://" + url[len(prefix):]
    return url


def run_migrations_online() -> None:
    engine = create_engine(_url(), future=True)
    with engine.connect() as connection:
        context.configure(connection=connection)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


run_migrations_online()
