from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from .config import settings
from .models import Stop, StopCreate


def _connect() -> sqlite3.Connection:
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with _connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS stops (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                stop_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                routes TEXT NOT NULL DEFAULT '[]'
            )
            """
        )


def _row_to_stop(row: sqlite3.Row) -> Stop:
    return Stop(
        id=row["id"],
        stop_id=row["stop_id"],
        name=row["name"],
        routes=json.loads(row["routes"]),
    )


def list_stops() -> list[Stop]:
    with _connect() as conn:
        rows = conn.execute("SELECT * FROM stops ORDER BY id").fetchall()
    return [_row_to_stop(r) for r in rows]


def upsert_stop(payload: StopCreate) -> Stop:
    routes_json = json.dumps(payload.routes, ensure_ascii=False)
    with _connect() as conn:
        row = conn.execute(
            """
            INSERT INTO stops (stop_id, name, routes) VALUES (?, ?, ?)
            ON CONFLICT(stop_id) DO UPDATE SET name=excluded.name, routes=excluded.routes
            RETURNING id, stop_id, name, routes
            """,
            (payload.stop_id, payload.name, routes_json),
        ).fetchone()
    return _row_to_stop(row)


def delete_stop(stop_id: str) -> bool:
    with _connect() as conn:
        cur = conn.execute("DELETE FROM stops WHERE stop_id = ?", (stop_id,))
        return cur.rowcount > 0
