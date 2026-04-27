"""Клиент к неофициальному masstransit API Яндекс.Карт.

Эндпоинт `https://yandex.ru/maps/api/masstransit/getStopInfo` отдаёт прогнозы
прибытия по идентификатору остановки (`stop__NNNNNNN`) без ключа, но требует
csrfToken и Session cookie, которые выдаёт обычная страница `/maps/`.
"""

from __future__ import annotations

import asyncio
import re
import time
from typing import Any

import httpx

from .config import settings
from .models import Arrival

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
CSRF_TTL_SECONDS = 30 * 60
BOOTSTRAP_URL = "https://yandex.ru/maps/"
STOP_INFO_URL = "https://yandex.ru/maps/api/masstransit/getStopInfo"
SEARCH_URL = "https://yandex.ru/maps/api/search"


class YandexError(RuntimeError):
    pass


class YandexMasstransit:
    def __init__(self) -> None:
        self._client = httpx.AsyncClient(
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "ru,en;q=0.9",
                "Accept": "application/json, text/plain, */*",
            },
            timeout=settings.request_timeout,
            follow_redirects=True,
        )
        self._csrf: str | None = None
        self._csrf_ts: float = 0.0
        self._lock = asyncio.Lock()

    async def close(self) -> None:
        await self._client.aclose()

    async def _ensure_csrf(self, force: bool = False) -> str:
        async with self._lock:
            if (
                not force
                and self._csrf
                and (time.time() - self._csrf_ts) < CSRF_TTL_SECONDS
            ):
                return self._csrf
            r = await self._client.get(BOOTSTRAP_URL)
            r.raise_for_status()
            match = re.search(r'"csrfToken"\s*:\s*"([^"]+)"', r.text)
            if not match:
                raise YandexError(
                    "csrfToken не найден на yandex.ru/maps/. Возможно, изменился вёрстка."
                )
            self._csrf = match.group(1)
            self._csrf_ts = time.time()
            return self._csrf

    async def _get_json(self, url: str, params: dict[str, Any]) -> dict[str, Any]:
        params = {**params, "csrfToken": await self._ensure_csrf()}
        r = await self._client.get(url, params=params)
        if r.status_code in (401, 403):
            params["csrfToken"] = await self._ensure_csrf(force=True)
            r = await self._client.get(url, params=params)
        if r.status_code >= 400:
            raise YandexError(
                f"{url} -> {r.status_code}: {r.text[:300]}"
            )
        try:
            return r.json()
        except ValueError as e:
            raise YandexError(f"Ответ не JSON: {r.text[:300]}") from e

    async def get_stop_info(self, stop_id: str) -> dict[str, Any]:
        return await self._get_json(
            STOP_INFO_URL,
            {"id": stop_id, "lang": settings.yandex_lang, "locale": settings.yandex_lang},
        )

    async def search(self, query: str) -> dict[str, Any]:
        return await self._get_json(
            SEARCH_URL,
            {"text": query, "lang": settings.yandex_lang, "type": "biz", "results": 10},
        )


def _walk(obj: Any, key: str) -> list[Any]:
    """Найти все значения по ключу `key` на любой глубине."""
    found: list[Any] = []
    stack: list[Any] = [obj]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            for k, v in cur.items():
                if k == key:
                    found.append(v)
                if isinstance(v, (dict, list)):
                    stack.append(v)
        elif isinstance(cur, list):
            stack.extend(cur)
    return found


def _extract_stop_name(payload: dict[str, Any]) -> str:
    for meta in _walk(payload, "StopMetaData"):
        if isinstance(meta, dict) and meta.get("name"):
            return str(meta["name"])
    for props in _walk(payload, "properties"):
        if isinstance(props, dict) and props.get("name"):
            return str(props["name"])
    return ""


def _extract_transport(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Достать список маршрутов из ответа getStopInfo, перебирая возможные ключи."""
    candidates: list[Any] = []
    for key in ("Transport", "transport"):
        candidates.extend(_walk(payload, key))
    flat: list[dict[str, Any]] = []
    for c in candidates:
        if isinstance(c, list):
            flat.extend(x for x in c if isinstance(x, dict))
    seen: set[int] = set()
    unique: list[dict[str, Any]] = []
    for item in flat:
        if id(item) in seen:
            continue
        seen.add(id(item))
        unique.append(item)
    return unique


def _direction_of(transport: dict[str, Any]) -> str | None:
    for key in ("EssentialStops", "essentialStops"):
        stops = transport.get(key)
        if isinstance(stops, list) and stops:
            last = stops[-1]
            if isinstance(last, dict) and last.get("name"):
                return str(last["name"])
    threads = transport.get("threads")
    if isinstance(threads, list) and threads:
        first = threads[0]
        if isinstance(first, dict) and first.get("name"):
            return str(first["name"])
    return None


def _arrivals_of(transport: dict[str, Any]) -> list[tuple[str, int | None]]:
    """Возвращает список (текст, секунды) для прогнозов конкретного маршрута."""
    results: list[tuple[str, int | None]] = []
    for key in ("Estimated", "estimated"):
        ests = transport.get(key)
        if isinstance(ests, list):
            for est in ests:
                if not isinstance(est, dict):
                    continue
                text = est.get("text") or ""
                seconds = est.get("value")
                results.append((str(text), int(seconds) if isinstance(seconds, (int, float)) else None))
    if results:
        return results
    brief = transport.get("BriefSchedule") or transport.get("briefSchedule")
    if isinstance(brief, dict):
        events = brief.get("Events") or brief.get("events") or []
        if isinstance(events, list):
            for ev in events:
                if not isinstance(ev, dict):
                    continue
                est = ev.get("Estimated") or ev.get("estimated")
                if isinstance(est, dict):
                    text = est.get("text") or ""
                    seconds = est.get("value")
                    results.append((str(text), int(seconds) if isinstance(seconds, (int, float)) else None))
                elif "Scheduled" in ev:
                    sch = ev["Scheduled"]
                    if isinstance(sch, dict):
                        results.append((str(sch.get("text") or "по расписанию"), None))
    return results


def parse_arrivals(payload: dict[str, Any]) -> tuple[str, list[Arrival]]:
    name = _extract_stop_name(payload)
    transport = _extract_transport(payload)
    arrivals: list[Arrival] = []
    for t in transport:
        route = (
            t.get("name")
            or t.get("shortName")
            or t.get("number")
            or ""
        )
        if not route:
            line = t.get("lineId") or {}
            if isinstance(line, dict):
                route = str(line.get("name") or "")
        ttype = str(t.get("type") or t.get("Type") or "")
        direction = _direction_of(t)
        ests = _arrivals_of(t)
        if not ests:
            arrivals.append(
                Arrival(route=str(route), type=ttype, direction=direction, eta_text="нет данных", eta_seconds=None)
            )
            continue
        for text, seconds in ests:
            arrivals.append(
                Arrival(route=str(route), type=ttype, direction=direction, eta_text=text, eta_seconds=seconds)
            )
    return name, arrivals
