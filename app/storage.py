from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from .config import settings
from .models import Stop, StopCreate
from .yandex import normalize_stop_id


class ReadOnlyError(RuntimeError):
    """Бросается при попытке мутировать остановки в env-режиме."""


def env_mode() -> bool:
    return bool(settings.stops_env.strip())


def _parse_env_stops() -> list[Stop]:
    raw = settings.stops_env.strip()
    if not raw:
        return []
    try:
        items = json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(
            f"Переменная STOPS должна быть JSON-массивом, ошибка: {e}"
        ) from e
    if not isinstance(items, list):
        raise RuntimeError("STOPS должна быть JSON-массивом объектов")
    out: list[Stop] = []
    for idx, item in enumerate(items, start=1):
        if not isinstance(item, dict):
            raise RuntimeError(f"STOPS[{idx-1}] не объект")
        try:
            out.append(
                Stop(
                    id=idx,
                    stop_id=normalize_stop_id(str(item["stop_id"])),
                    name=str(item.get("name") or item["stop_id"]),
                    routes=[str(r) for r in (item.get("routes") or [])],
                )
            )
        except KeyError as e:
            raise RuntimeError(f"STOPS[{idx-1}] пропущен ключ {e}") from e
    return out


def _connect() -> sqlite3.Connection:
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    if env_mode():
        # Прогоняем парсинг чтобы упасть рано, если STOPS невалиден
        _parse_env_stops()
        return
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
    if env_mode():
        return _parse_env_stops()
    with _connect() as conn:
        rows = conn.execute("SELECT * FROM stops ORDER BY id").fetchall()
    return [_row_to_stop(r) for r in rows]


def upsert_stop(payload: StopCreate) -> Stop:
    if env_mode():
        raise ReadOnlyError(
            "Сервис в env-режиме (задана переменная STOPS). Редактируй её в Railway → Variables."
        )
    routes_json = json.dumps(payload.routes, ensure_ascii=False)
    canonical_id = normalize_stop_id(payload.stop_id)
    with _connect() as conn:
        row = conn.execute(
            """
            INSERT INTO stops (stop_id, name, routes) VALUES (?, ?, ?)
            ON CONFLICT(stop_id) DO UPDATE SET name=excluded.name, routes=excluded.routes
            RETURNING id, stop_id, name, routes
            """,
            (canonical_id, payload.name, routes_json),
        ).fetchone()
    return _row_to_stop(row)


def delete_stop(stop_id: str) -> bool:
    if env_mode():
        raise ReadOnlyError(
            "Сервис в env-режиме (задана переменная STOPS). Редактируй её в Railway → Variables."
        )
    with _connect() as conn:
        cur = conn.execute(
            "DELETE FROM stops WHERE stop_id = ?", (normalize_stop_id(stop_id),)
        )
        return cur.rowcount > 0
