"""Простой счётчик визитов: один SQLite-файл, key→count и часовые бакеты."""

from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .config import settings

MSK = timezone(timedelta(hours=3))


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
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS hourly_visits (
                key TEXT NOT NULL,
                hour TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (key, hour)
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_hour ON hourly_visits(key, hour)"
        )


def increment(key: str) -> int:
    """Увеличивает счётчики (общий и часовой) и возвращает общий новый count."""
    hour = datetime.now(MSK).strftime("%Y-%m-%dT%H")
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
        conn.execute(
            """
            INSERT INTO hourly_visits (key, hour, count) VALUES (?, ?, 1)
            ON CONFLICT(key, hour) DO UPDATE SET count = count + 1
            """,
            (key, hour),
        )
    return int(row["count"]) if row else 0


def get(key: str) -> int:
    with _connect() as conn:
        row = conn.execute(
            "SELECT count FROM visits WHERE key = ?", (key,)
        ).fetchone()
    return int(row["count"]) if row else 0


def hourly_buckets(key: str, hours: int = 24) -> list[tuple[str, int]]:
    """Возвращает список (hour_label, count) длиной `hours`, начиная hours назад
    и заканчивая текущим часом включительно. Пропуски дозаполняются нулями.
    Метки часов в МСК."""
    now = datetime.now(MSK).replace(minute=0, second=0, microsecond=0)
    start = now - timedelta(hours=hours - 1)
    cutoff = start.strftime("%Y-%m-%dT%H")
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT hour, count FROM hourly_visits
            WHERE key = ? AND hour >= ?
            ORDER BY hour
            """,
            (key, cutoff),
        ).fetchall()
    by_hour: dict[str, int] = {r["hour"]: int(r["count"]) for r in rows}
    out: list[tuple[str, int]] = []
    for i in range(hours):
        h = (start + timedelta(hours=i)).strftime("%Y-%m-%dT%H")
        out.append((h, by_hour.get(h, 0)))
    return out
