"""Клиент для прогнозов прибытия с Яндекс.Карт.

Подход через `api/masstransit/getStopInfo` теперь блокируется Антиботом
(возвращает только `{csrfToken: ...}` без данных). Поэтому сейчас работаем
скрейпом HTML-страницы остановки `https://yandex.ru/maps/213/moscow/stops/<n>/`
и достаём встроенный state из тега `<script class="config-view">`.
"""

from __future__ import annotations

import asyncio
import html as html_lib
import json
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
SESSION_TTL_SECONDS = 30 * 60
BOOTSTRAP_URL = "https://yandex.ru/maps/"
STOP_PAGE_URL = "https://yandex.ru/maps/213/moscow/stops/{numeric}/?lang=ru"
STOP_INFO_URL = "https://yandex.ru/maps/api/masstransit/getStopInfo"
SEARCH_URL = "https://yandex.ru/maps/api/search"


def normalize_stop_id(value: str) -> str:
    """Принимает числовой ID из URL Я.Карт (.../stops/5854295457/) или
    канонический stop__5854295457; возвращает канонический."""
    value = value.strip()
    if value.isdigit():
        return f"stop__{value}"
    return value


def _numeric_id(stop_id: str) -> str:
    return normalize_stop_id(stop_id).removeprefix("stop__")


class YandexError(RuntimeError):
    pass


class YandexMasstransit:
    def __init__(self) -> None:
        self._client = httpx.AsyncClient(
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "ru,en;q=0.9",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer": BOOTSTRAP_URL,
            },
            timeout=settings.request_timeout,
            follow_redirects=True,
        )
        self._csrf: str | None = None
        self._session_id: str | None = None
        self._session_ts: float = 0.0
        self._lock = asyncio.Lock()

    async def close(self) -> None:
        await self._client.aclose()

    async def _ensure_session(self, force: bool = False) -> tuple[str, str]:
        """Старая API-логика на случай если когда-нибудь снова заработает."""
        async with self._lock:
            if (
                not force
                and self._csrf
                and self._session_id
                and (time.time() - self._session_ts) < SESSION_TTL_SECONDS
            ):
                return self._csrf, self._session_id
            r = await self._client.get(BOOTSTRAP_URL)
            r.raise_for_status()
            csrf_match = re.search(r'"csrfToken"\s*:\s*"([^"]+)"', r.text)
            session_match = re.search(r'"sessionId"\s*:\s*"([^"]+)"', r.text)
            if not csrf_match:
                raise YandexError("csrfToken не найден на yandex.ru/maps/.")
            self._csrf = csrf_match.group(1)
            self._session_id = (
                session_match.group(1) if session_match else str(int(time.time() * 1000))
            )
            self._session_ts = time.time()
            return self._csrf, self._session_id

    async def fetch_stop_html(self, stop_id: str) -> str:
        """Сырая HTML-страница остановки. Полезно для отладки."""
        url = STOP_PAGE_URL.format(numeric=_numeric_id(stop_id))
        r = await self._client.get(url)
        if r.status_code >= 400:
            raise YandexError(f"{url} -> {r.status_code}: {r.text[:300]}")
        return r.text

    async def get_stop_state(self, stop_id: str) -> dict[str, Any]:
        """HTML-страница → встроенный state из <script class="config-view">."""
        html = await self.fetch_stop_html(stop_id)
        return _extract_state(html)

    # старое API-обращение, оставляю на всякий случай (сейчас возвращает пустоту)
    async def get_stop_info_api(self, stop_id: str) -> dict[str, Any]:
        csrf, session_id = await self._ensure_session()
        params = {
            "id": normalize_stop_id(stop_id),
            "lang": settings.yandex_lang,
            "locale": settings.yandex_lang,
            "csrfToken": csrf,
            "sessionId": session_id,
        }
        r = await self._client.get(STOP_INFO_URL, params=params)
        if r.status_code >= 400:
            raise YandexError(f"{STOP_INFO_URL} -> {r.status_code}: {r.text[:300]}")
        try:
            return r.json()
        except ValueError as e:
            raise YandexError(f"Ответ не JSON: {r.text[:300]}") from e

    async def search(self, query: str) -> dict[str, Any]:
        csrf, session_id = await self._ensure_session()
        params = {
            "text": query,
            "lang": settings.yandex_lang,
            "type": "biz",
            "results": 10,
            "csrfToken": csrf,
            "sessionId": session_id,
        }
        r = await self._client.get(SEARCH_URL, params=params)
        if r.status_code >= 400:
            raise YandexError(f"{SEARCH_URL} -> {r.status_code}: {r.text[:300]}")
        try:
            return r.json()
        except ValueError as e:
            raise YandexError(f"Ответ не JSON: {r.text[:300]}") from e


_STATE_PATTERNS = [
    # Главный — Я.Карты прячут initial state в <script class="state-view">
    re.compile(
        r'<script[^>]*class="[^"]*state-view[^"]*"[^>]*>(.*?)</script>',
        re.DOTALL,
    ),
    re.compile(
        r'<script[^>]*class="[^"]*config-view[^"]*"[^>]*>(.*?)</script>',
        re.DOTALL,
    ),
    re.compile(
        r'<script[^>]*data-name="config"[^>]*>(.*?)</script>',
        re.DOTALL,
    ),
    re.compile(
        r'window\.__APP_STATE__\s*=\s*(\{.*?\});\s*</script>',
        re.DOTALL,
    ),
    re.compile(
        r'window\.__INITIAL_STATE__\s*=\s*(\{.*?\});\s*</script>',
        re.DOTALL,
    ),
]


def _extract_state(html: str) -> dict[str, Any]:
    last_err: str | None = None
    for pattern in _STATE_PATTERNS:
        m = pattern.search(html)
        if not m:
            continue
        content = m.group(1).strip()
        if "&quot;" in content or "&amp;" in content:
            content = html_lib.unescape(content)
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            last_err = str(e)
            continue
    raise YandexError(
        "Не нашёл встроенный state в HTML. "
        f"Возможно, Антиробот вернул заглушку. {('Ошибка парсинга: ' + last_err) if last_err else ''}"
    )


_NAME_DIRECTION_RE = re.compile(r"^(\S+)\s*(?:\((.+?)\))?\s*$")


def _split_route_name(full: str) -> tuple[str, str | None]:
    """Из 'м27 (МЦД Остафьево)' → ('м27', 'МЦД Остафьево')."""
    m = _NAME_DIRECTION_RE.match(full.strip())
    if not m:
        return full.strip(), None
    return m.group(1), (m.group(2) or None)


def _server_now(state: dict[str, Any]) -> int:
    """Unix-время сервера: предпочитаем config.serverTime, иначе системные now."""
    config = state.get("config")
    if isinstance(config, dict):
        st = config.get("serverTime")
        if isinstance(st, str):
            try:
                from datetime import datetime as _dt

                ts = _dt.fromisoformat(st.replace("Z", "+00:00"))
                return int(ts.timestamp())
            except ValueError:
                pass
    return int(time.time())


def _eta_from_value(value: Any, now_ts: int) -> int | None:
    """Поле .value у Scheduled/Estimated может быть строкой/числом, абс. unix-time
    (предпочитают это) или секундами от сейчас."""
    try:
        v = int(value)
    except (TypeError, ValueError):
        return None
    if v <= 0:
        return None
    # > сегодняшней полночи 1970 + 30 лет → точно абсолютная метка
    if v > 1_000_000_000:
        return max(0, v - now_ts)
    return v


def _events_of_thread(thread: dict[str, Any]) -> list[tuple[str, int | None, bool]]:
    """Возвращает [(текст, eta_sec, real_time)]; real_time=True если Estimated."""
    out: list[tuple[str, int | None, bool]] = []
    brief = thread.get("BriefSchedule") or thread.get("briefSchedule")
    if not isinstance(brief, dict):
        return out
    events = brief.get("Events") or brief.get("events") or []
    if not isinstance(events, list):
        return out
    # _server_now прокинуть нельзя сюда без рефакторинга; оставлю заглушку — пересчитаю в вызывающей.
    for ev in events:
        if not isinstance(ev, dict):
            continue
        est = ev.get("Estimated") or ev.get("estimated")
        if isinstance(est, dict):
            text = str(est.get("text") or "")
            out.append((text, est.get("value"), True))
            continue
        sch = ev.get("Scheduled") or ev.get("scheduled")
        if isinstance(sch, dict):
            text = str(sch.get("text") or "по расписанию")
            out.append((text, sch.get("value"), False))
    return out


def parse_arrivals(state: dict[str, Any]) -> tuple[str, list[Arrival]]:
    """Парсит SSR-state Я.Карт остановочной страницы.

    Путь к данным: state['stack'][0]['stops']['data'] → {name, transports: [...]}.
    Каждый transport имеет name='925 (МЦД Остафьево)', threads (по направлениям),
    у каждого thread есть BriefSchedule.Events с Estimated/Scheduled.
    """
    name = ""
    transports: list[dict[str, Any]] = []

    stack = state.get("stack")
    if isinstance(stack, list) and stack:
        first = stack[0]
        if isinstance(first, dict):
            stops_obj = first.get("stops")
            if isinstance(stops_obj, dict):
                data = stops_obj.get("data")
                if isinstance(data, dict):
                    if isinstance(data.get("name"), str):
                        name = data["name"]
                    raw_transports = data.get("transports") or []
                    if isinstance(raw_transports, list):
                        transports = [t for t in raw_transports if isinstance(t, dict)]

    now_ts = _server_now(state)
    arrivals: list[Arrival] = []

    for t in transports:
        full_name = str(t.get("name") or "")
        route, dir_from_name = _split_route_name(full_name)
        ttype = str(t.get("type") or "")
        threads = t.get("threads")
        if not isinstance(threads, list):
            threads = []
        wrote_any = False
        for thread in threads:
            if not isinstance(thread, dict):
                continue
            if thread.get("noBoarding"):
                continue
            direction = dir_from_name
            ess = thread.get("EssentialStops")
            if isinstance(ess, list) and ess:
                last = ess[-1]
                if isinstance(last, dict) and last.get("name"):
                    direction = str(last["name"])
            for text, value, real_time in _events_of_thread(thread):
                eta_seconds = _eta_from_value(value, now_ts)
                if not real_time and not text and eta_seconds is None:
                    continue
                arrivals.append(
                    Arrival(
                        route=route,
                        type=ttype,
                        direction=direction,
                        eta_text=text or "по расписанию",
                        eta_seconds=eta_seconds,
                    )
                )
                wrote_any = True
        if not wrote_any:
            arrivals.append(
                Arrival(
                    route=route,
                    type=ttype,
                    direction=dir_from_name,
                    eta_text="нет данных",
                    eta_seconds=None,
                )
            )
    return name, arrivals
