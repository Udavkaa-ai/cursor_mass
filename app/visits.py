"""Заглушка счётчика визитов — статистика отключена."""

from __future__ import annotations


async def init() -> None:
    pass


async def close() -> None:
    pass


async def increment(key: str) -> int:
    return 0


async def get(key: str) -> int:
    return 0


async def hourly_buckets(key: str, hours: int = 24) -> list[tuple[str, int]]:
    return []
