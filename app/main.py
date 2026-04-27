from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse

from . import storage, yandex
from .config import settings
from .models import Arrival, Stop, StopArrivals, StopCreate

masstransit: yandex.YandexMasstransit | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global masstransit
    storage.init_db()
    masstransit = yandex.YandexMasstransit()
    try:
        yield
    finally:
        await masstransit.close()


app = FastAPI(title="Bus Arrival Tracker", lifespan=lifespan)


def require_api_key(x_api_key: str = Header(default="")) -> None:
    if not settings.api_key:
        return
    if x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="invalid or missing X-API-Key")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _filter(arrivals: list[Arrival], routes: list[str]) -> list[Arrival]:
    if not routes:
        return arrivals
    wanted = {r.strip().lower() for r in routes if r.strip()}
    return [a for a in arrivals if a.route.lower() in wanted]


@app.get("/health")
async def health() -> dict[str, bool]:
    return {"ok": True}


_INDEX_HTML = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f0f10">
<title>Автобусы</title>
<style>
:root { color-scheme: dark; --bg:#0f0f10; --card:#1a1a1d; --muted:#8a8a90; --fg:#f4f4f6; --accent:#42d883; --warn:#ffaa33; }
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:max(env(safe-area-inset-top),12px) 12px max(env(safe-area-inset-bottom),12px) 12px}
header{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;font-size:13px;color:var(--muted)}
header button{background:none;border:1px solid #333;color:var(--fg);padding:6px 12px;border-radius:8px;font:inherit;font-size:13px}
.stop{background:var(--card);border-radius:14px;padding:14px 14px 6px;margin-bottom:12px}
.stop h2{margin:0 0 8px;font-size:17px;font-weight:600}
.row{display:flex;align-items:baseline;gap:10px;padding:8px 0;border-top:1px solid #232328}
.row:first-of-type{border-top:none}
.route{font-weight:700;font-size:18px;min-width:54px;color:var(--accent)}
.dir{flex:1;color:var(--muted);font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.eta{font-weight:600;font-size:17px}
.eta.scheduled{color:var(--warn);font-weight:500}
.empty{color:var(--muted);font-style:italic;font-size:14px;padding:6px 0}
.error{color:#ff6b6b;font-size:13px;padding:6px 0}
.spinner{display:inline-block;width:14px;height:14px;border:2px solid #333;border-top-color:var(--fg);border-radius:50%;animation:spin .8s linear infinite;vertical-align:middle}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<header>
  <span id="status">обновляется… <span class="spinner"></span></span>
  <button onclick="load(true)">Обновить</button>
</header>
<main id="root"></main>
<script>
const $ = (sel) => document.querySelector(sel);
const root = $('#root');
const status = $('#status');

function eta_class(a) {
  if (a.eta_seconds == null) return 'eta scheduled';
  return 'eta';
}

function renderStop(s) {
  const arrivals = (s.arrivals || []);
  let body = arrivals.length
    ? arrivals.map(a => `
        <div class="row">
          <span class="route">${a.route || '—'}</span>
          <span class="dir">${a.direction || ''}</span>
          <span class="${eta_class(a)}">${a.eta_local || a.eta_text || ''}</span>
        </div>`).join('')
    : '<div class="empty">нет прибытий</div>';
  return `<section class="stop"><h2>${s.name || s.stop_id}</h2>${body}</section>`;
}

async function load(force) {
  if (force) status.innerHTML = 'обновляется… <span class="spinner"></span>';
  try {
    const r = await fetch('/arrivals', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    root.innerHTML = data.length
      ? data.map(renderStop).join('')
      : '<div class="empty">остановок не задано (см. STOPS env)</div>';
    const now = new Date();
    status.textContent = 'обновлено ' + now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch (e) {
    status.innerHTML = '<span class="error">ошибка: ' + e.message + '</span>';
  }
}

load(true);
setInterval(load, 30000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
</script>
</body>
</html>
"""


@app.get("/", response_class=HTMLResponse)
async def index_page() -> HTMLResponse:
    return HTMLResponse(_INDEX_HTML)


@app.get("/stops", dependencies=[Depends(require_api_key)])
async def list_stops_endpoint() -> list[Stop]:
    return storage.list_stops()


@app.post(
    "/stops",
    dependencies=[Depends(require_api_key)],
    status_code=201,
)
async def add_stop_endpoint(payload: StopCreate) -> Stop:
    try:
        return storage.upsert_stop(payload)
    except storage.ReadOnlyError as e:
        raise HTTPException(status_code=409, detail=str(e))


@app.delete(
    "/stops/{stop_id}",
    dependencies=[Depends(require_api_key)],
    status_code=204,
    response_class=Response,
)
async def delete_stop_endpoint(stop_id: str) -> Response:
    try:
        deleted = storage.delete_stop(stop_id)
    except storage.ReadOnlyError as e:
        raise HTTPException(status_code=409, detail=str(e))
    if not deleted:
        raise HTTPException(status_code=404, detail="stop not found")
    return Response(status_code=204)


@app.get("/arrivals", dependencies=[Depends(require_api_key)])
async def all_arrivals_endpoint() -> list[StopArrivals]:
    assert masstransit is not None
    out: list[StopArrivals] = []
    for s in storage.list_stops():
        try:
            payload = await masstransit.get_stop_state(s.stop_id)
        except yandex.YandexError as e:
            out.append(
                StopArrivals(
                    stop_id=s.stop_id,
                    name=s.name,
                    arrivals=[Arrival(route="", eta_text=f"ошибка: {e}")],
                    fetched_at=_now_iso(),
                )
            )
            continue
        name, arrivals = yandex.parse_arrivals(payload)
        out.append(
            StopArrivals(
                stop_id=s.stop_id,
                name=s.name or name,
                arrivals=_filter(arrivals, s.routes),
                fetched_at=_now_iso(),
            )
        )
    return out


@app.get("/arrivals/{stop_id}", dependencies=[Depends(require_api_key)])
async def stop_arrivals_endpoint(
    stop_id: str,
    routes: str | None = Query(default=None, description="CSV маршрутов: 925,907"),
) -> StopArrivals:
    assert masstransit is not None
    payload = await masstransit.get_stop_state(stop_id)
    name, arrivals = yandex.parse_arrivals(payload)
    routes_list = [r for r in (routes.split(",") if routes else [])]
    return StopArrivals(
        stop_id=stop_id,
        name=name,
        arrivals=_filter(arrivals, routes_list),
        fetched_at=_now_iso(),
    )


@app.get("/raw/{stop_id}", dependencies=[Depends(require_api_key)])
async def raw_stop_endpoint(stop_id: str) -> JSONResponse:
    """Распарсенный встроенный state из HTML-страницы остановки."""
    assert masstransit is not None
    payload = await masstransit.get_stop_state(stop_id)
    return JSONResponse(payload)


@app.get("/state/{stop_id}", dependencies=[Depends(require_api_key)])
async def state_navigate_endpoint(
    stop_id: str,
    path: str = Query(default="", description="Точечный путь по JSON: 'data.stop.transport[0]'"),
    keys_only: bool = Query(default=True, description="True = только список ключей; False = всё значение"),
    max_chars: int = Query(default=8000, ge=100, le=200_000),
) -> JSONResponse:
    """Навигатор по большому SSR-state. Сначала смотришь верхние ключи
    (`?path=`), потом углубляешься (`?path=data.stop&keys_only=false`)."""
    assert masstransit is not None
    state = await masstransit.get_stop_state(stop_id)

    cur: object = state
    for token in [t for t in path.split(".") if t]:
        idx_match = None
        if "[" in token and token.endswith("]"):
            idx_match = token[token.index("[") + 1 : -1]
            token = token[: token.index("[")]
        if isinstance(cur, dict):
            if token not in cur:
                return JSONResponse(
                    {"error": f"ключ '{token}' не найден", "available": sorted(cur.keys())[:50]},
                    status_code=404,
                )
            cur = cur[token]
        elif isinstance(cur, list):
            try:
                cur = cur[int(token)]
            except (ValueError, IndexError):
                return JSONResponse(
                    {"error": f"индекс '{token}' невалиден", "list_len": len(cur)},
                    status_code=404,
                )
        else:
            return JSONResponse(
                {"error": f"невозможно зайти в '{token}': значение не объект/список", "type": type(cur).__name__},
                status_code=404,
            )
        if idx_match is not None and isinstance(cur, list):
            try:
                cur = cur[int(idx_match)]
            except (ValueError, IndexError):
                return JSONResponse(
                    {"error": f"индекс [{idx_match}] невалиден", "list_len": len(cur)},
                    status_code=404,
                )

    summary: dict[str, object] = {"path": path, "type": type(cur).__name__}
    if isinstance(cur, dict):
        summary["keys"] = sorted(cur.keys())
        summary["size"] = len(cur)
        if not keys_only:
            import json as _json

            dumped = _json.dumps(cur, ensure_ascii=False)
            summary["truncated"] = len(dumped) > max_chars
            summary["value"] = (
                _json.loads(dumped[:max_chars] + ("..." if len(dumped) > max_chars else ""))
                if False
                else cur
                if len(dumped) <= max_chars
                else dumped[:max_chars] + "..."
            )
    elif isinstance(cur, list):
        summary["len"] = len(cur)
        summary["item_types"] = list({type(x).__name__ for x in cur[:20]})
        if not keys_only:
            summary["value"] = cur
    else:
        summary["value"] = cur
    return JSONResponse(summary)


@app.get("/raw_html/{stop_id}", dependencies=[Depends(require_api_key)])
async def raw_html_endpoint(stop_id: str) -> PlainTextResponse:
    """Сырая HTML-страница (первые 50 КБ) — для отладки скрейпинга."""
    assert masstransit is not None
    html = await masstransit.fetch_stop_html(stop_id)
    return PlainTextResponse(html[:50_000], media_type="text/plain; charset=utf-8")


@app.get("/scripts/{stop_id}", dependencies=[Depends(require_api_key)])
async def scripts_endpoint(stop_id: str) -> JSONResponse:
    """Все <script> блоки страницы с JSON-подобным телом, отсортированные по
    длине. Помогает быстро найти где в HTML спрятан state."""
    import re as _re

    assert masstransit is not None
    html = await masstransit.fetch_stop_html(stop_id)
    out = []
    pattern = _re.compile(
        r"<script([^>]*)>(.*?)</script>",
        _re.DOTALL | _re.IGNORECASE,
    )
    for idx, m in enumerate(pattern.finditer(html)):
        attrs = m.group(1).strip()
        body = m.group(2).strip()
        if not body:
            continue
        looks_jsonish = body.startswith("{") or body.startswith("[") or "&quot;" in body[:200]
        if not looks_jsonish:
            continue
        out.append(
            {
                "idx": idx,
                "attrs": attrs[:200],
                "length": len(body),
                "preview": body[:400],
            }
        )
    out.sort(key=lambda x: -x["length"])
    return JSONResponse({"total_html": len(html), "json_scripts": out[:20]})


@app.get("/raw_api/{stop_id}", dependencies=[Depends(require_api_key)])
async def raw_api_endpoint(stop_id: str) -> JSONResponse:
    """Старое API-обращение (сейчас обычно пусто; оставлено для проверки)."""
    assert masstransit is not None
    payload = await masstransit.get_stop_info_api(stop_id)
    return JSONResponse(payload)


@app.get("/search", dependencies=[Depends(require_api_key)])
async def search_endpoint(q: str = Query(..., min_length=2)) -> JSONResponse:
    """Поиск по Яндексу. Используй чтобы вытащить stop_id по названию."""
    assert masstransit is not None
    payload = await masstransit.search(q)
    return JSONResponse(payload)
