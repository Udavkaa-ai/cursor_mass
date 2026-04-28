"""Простой счётчик визитов: один SQLite-файл, key→count."""

from __future__ import annotations

import sqlite3
from pathlib import Path

from .config import settings


def _connect() -> sqlite3.Connection:
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    return conn


def init() -> None:
    with _connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS visits (
                key TEXT PRIMARY KEY,
                count INTEGER NOT NULL DEFAULT 0,
                last_visit TEXT
            )
            """
        )


def increment(key: str) -> int:
    """Увеличивает счётчик на 1 и возвращает новое значение."""
    with _connect() as conn:
        row = conn.execute(
            """
            INSERT INTO visits (key, count, last_visit)
            VALUES (?, 1, datetime('now'))
            ON CONFLICT(key) DO UPDATE SET
                count = count + 1,
                last_visit = datetime('now')
            RETURNING count
            """,
            (key,),
        ).fetchone()
    return int(row["count"]) if row else 0


def get(key: str) -> int:
    with _connect() as conn:
        row = conn.execute(
            "SELECT count FROM visits WHERE key = ?", (key,)
        ).fetchone()
    return int(row["count"]) if row else 0
