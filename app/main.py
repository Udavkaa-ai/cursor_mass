from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse

from . import mosgortrans, storage, yandex
from .config import settings
from .models import Arrival, Stop, StopArrivals, StopCreate
from pathlib import Path

masstransit: yandex.YandexMasstransit | None = None
mos_client: mosgortrans.MosClient | None = None

_YC_PROXY_PATH = Path(__file__).parent.parent / "proxy" / "yc_function.py"


@asynccontextmanager
async def lifespan(app: FastAPI):
    global masstransit, mos_client
    storage.init_db()
    masstransit = yandex.YandexMasstransit()
    if settings.mos_api_key:
        mos_client = mosgortrans.MosClient()
    try:
        yield
    finally:
        await masstransit.close()
        if mos_client is not None:
            await mos_client.close()


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
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>Автобусы</title>
<style>
:root { color-scheme: dark; --bg:#0f0f10; --card:#1a1a1d; --muted:#7a7a80; --fg:#f4f4f6;
        --run:#ff4d4d; --hurry:#ffaa33; --walk:#42d883; --calm:#9aa1aa; --sched:#7ea0ff; }
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:max(env(safe-area-inset-top),14px) 14px max(env(safe-area-inset-bottom),14px) 14px}
header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;font-size:13px;color:var(--muted)}
header button{background:#1a1a1d;border:1px solid #2a2a2e;color:var(--fg);padding:8px 14px;border-radius:10px;font:inherit;font-size:14px}
.stop{background:var(--card);border-radius:16px;padding:16px;margin-bottom:14px}
.stop h2{margin:0 0 12px;font-size:17px;font-weight:600;color:var(--muted);letter-spacing:.2px}
.stop h2 a{color:inherit;text-decoration:none}
.stop h2 a:active{opacity:.6}
.route-block{margin-top:14px}
.route-block:first-of-type{margin-top:0}
.route-head{display:flex;align-items:baseline;gap:10px;margin-bottom:6px}
.route{font-weight:800;font-size:24px;color:var(--fg)}
.dir{flex:1;color:var(--muted);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.eta-main{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}
.eta{font-weight:800;font-size:34px;line-height:1.05;letter-spacing:-.5px}
.eta.run{color:var(--run)}
.eta.hurry{color:var(--hurry)}
.eta.walk{color:var(--walk)}
.eta.calm{color:var(--fg)}
.eta.sched{color:var(--sched);font-weight:600}
.eta.next{font-size:18px;color:var(--muted);font-weight:500}
.hint{display:block;font-size:13px;line-height:1;margin-top:6px;letter-spacing:.2px;text-transform:uppercase;font-weight:600}
.hint.run{color:var(--run)}
.hint.hurry{color:var(--hurry)}
.hint.walk{color:var(--walk)}
.empty{color:var(--muted);font-style:italic;font-size:14px;padding:6px 0}
.stale{color:var(--muted);font-size:13px;padding:6px 0;display:flex;align-items:center;gap:6px}
.stale::before{content:"";display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--muted)}
.error{color:#ff6b6b;font-size:13px;padding:6px 0}
.spinner{display:inline-block;width:12px;height:12px;border:2px solid #2a2a2e;border-top-color:var(--fg);border-radius:50%;animation:spin .8s linear infinite;vertical-align:middle;margin-left:4px}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<header>
  <span id="status">обновляется<span class="spinner"></span></span>
  <button onclick="load(true)">Обновить</button>
</header>
<main id="root"></main>
<script>
const $ = (sel) => document.querySelector(sel);
const root = $('#root');
const statusEl = $('#status');
const MAX_ARRIVALS_PER_ROUTE = 3;

function urgencyInfo(secs) {
  if (secs == null) return { cls: 'sched', hint: '' };
  if (secs <= 180) return { cls: 'run', hint: 'подъезжает' };
  if (secs <= 300) return { cls: 'hurry', hint: 'скоро будет' };
  if (secs <= 420) return { cls: 'walk', hint: 'можно не торопиться' };
  return { cls: 'calm', hint: '' };
}

function groupByRoute(arrivals) {
  const map = new Map();
  for (const a of arrivals) {
    const key = (a.route || '?') + '|' + (a.direction || '');
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(a);
  }
  for (const list of map.values()) {
    list.sort((x, y) => (x.eta_seconds ?? 1e12) - (y.eta_seconds ?? 1e12));
  }
  return [...map.values()].sort((a, b) => (a[0].eta_seconds ?? 1e12) - (b[0].eta_seconds ?? 1e12));
}

function renderRoute(group) {
  const head = group[0];
  const items = group.slice(0, MAX_ARRIVALS_PER_ROUTE);
  const main = items[0];
  const rest = items.slice(1);
  const u = urgencyInfo(main.eta_seconds);
  const hint = u.hint ? `<span class="hint ${u.cls}">${u.hint}</span>` : '';
  return `
    <div class="route-block">
      <div class="route-head">
        <span class="route">${head.route || '—'}</span>
        <span class="dir">${head.direction || ''}</span>
      </div>
      <div class="eta-main">
        <span class="eta ${u.cls}">${main.eta_local || main.eta_text || ''}</span>
        ${rest.map(a => `<span class="eta next">${a.eta_local || a.eta_text || ''}</span>`).join('')}
      </div>
      ${hint}
    </div>`;
}

function renderStop(s) {
  const link = `/stop/${encodeURIComponent(s.stop_id)}`;
  const title = `<h2><a href="${link}">${s.name || s.stop_id} →</a></h2>`;
  if (s.error) {
    return `<section class="stop">${title}<div class="stale">данные сейчас недоступны</div></section>`;
  }
  if (!s.arrivals || !s.arrivals.length) {
    return `<section class="stop">${title}<div class="empty">нет прибытий</div></section>`;
  }
  const groups = groupByRoute(s.arrivals);
  return `<section class="stop">${title}${groups.map(renderRoute).join('')}</section>`;
}

async function load(force) {
  if (force) statusEl.innerHTML = 'обновляется<span class="spinner"></span>';
  try {
    const r = await fetch('/arrivals', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    root.innerHTML = data.length
      ? data.map(renderStop).join('')
      : '<div class="empty">остановок не задано (см. переменную STOPS)</div>';
    const now = new Date();
    statusEl.textContent = now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch (e) {
    statusEl.innerHTML = '<span class="error">ошибка: ' + e.message + '</span>';
  }
}

load(true);
document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
</script>
</body>
</html>
"""


_STOP_HTML = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f0f10">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>__TITLE__</title>
<style>
:root { color-scheme: dark; --bg:#0f0f10; --card:#1a1a1d; --muted:#7a7a80; --fg:#f4f4f6;
        --run:#ff4d4d; --hurry:#ffaa33; --walk:#42d883; --calm:#9aa1aa; --sched:#7ea0ff; }
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:max(env(safe-area-inset-top),18px) 16px max(env(safe-area-inset-bottom),18px) 16px;display:flex;flex-direction:column;min-height:100vh}
header{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px}
.title{font-size:18px;font-weight:600;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;margin-right:10px}
.refresh{background:#1a1a1d;border:1px solid #2a2a2e;color:var(--fg);padding:10px 18px;border-radius:12px;font:inherit;font-size:15px;font-weight:500;flex-shrink:0}
.refresh:active{background:#2a2a2e}
main{flex:1}
.route-block{margin-top:22px;padding-bottom:22px;border-bottom:1px solid #232328}
.route-block:first-of-type{margin-top:6px}
.route-block:last-of-type{border-bottom:none}
.route-head{display:flex;align-items:baseline;gap:12px;margin-bottom:10px}
.route{font-weight:800;font-size:30px;color:var(--fg);letter-spacing:-.5px}
.dir{flex:1;color:var(--muted);font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.eta-main{display:flex;align-items:baseline;gap:14px;flex-wrap:wrap}
.eta{font-weight:800;font-size:48px;line-height:1.05;letter-spacing:-1px}
.eta.run{color:var(--run)}
.eta.hurry{color:var(--hurry)}
.eta.walk{color:var(--walk)}
.eta.calm{color:var(--fg)}
.eta.sched{color:var(--sched);font-weight:600;font-size:34px}
.eta.next{font-size:20px;color:var(--muted);font-weight:500}
.hint{display:block;font-size:14px;line-height:1;margin-top:10px;letter-spacing:.4px;text-transform:uppercase;font-weight:700}
.hint.run{color:var(--run)}
.hint.hurry{color:var(--hurry)}
.hint.walk{color:var(--walk)}
.empty{color:var(--muted);font-style:italic;font-size:15px;padding:40px 0;text-align:center}
.stale{color:var(--muted);font-size:14px;padding:40px 0;text-align:center}
.error{color:#ff6b6b;font-size:14px;padding:40px 0;text-align:center}
.spinner{display:inline-block;width:14px;height:14px;border:2px solid #2a2a2e;border-top-color:var(--fg);border-radius:50%;animation:spin .8s linear infinite;vertical-align:middle;margin-left:6px}
.footer{margin-top:auto;padding-top:18px;color:var(--muted);font-size:12px;text-align:center}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<header>
  <div class="title" id="title">__TITLE__</div>
  <button class="refresh" id="refresh">Обновить</button>
</header>
<main id="root"><div class="empty">загрузка<span class="spinner"></span></div></main>
<div class="footer" id="footer">—</div>
<script>
const STOP_ID = "__STOP_ID__";
const ROUTES = "__ROUTES__"; // CSV
const root = document.getElementById('root');
const footer = document.getElementById('footer');
const btn = document.getElementById('refresh');
const titleEl = document.getElementById('title');
const MAX_NEXT = 2;

function urgencyInfo(secs) {
  if (secs == null) return { cls: 'sched', hint: '' };
  if (secs <= 180) return { cls: 'run', hint: 'подъезжает' };
  if (secs <= 300) return { cls: 'hurry', hint: 'скоро будет' };
  if (secs <= 420) return { cls: 'walk', hint: 'можно не торопиться' };
  return { cls: 'calm', hint: '' };
}

function groupByRoute(arrivals) {
  const map = new Map();
  for (const a of arrivals) {
    const key = (a.route || '?') + '|' + (a.direction || '');
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(a);
  }
  for (const list of map.values()) {
    list.sort((x, y) => (x.eta_seconds ?? 1e12) - (y.eta_seconds ?? 1e12));
  }
  return [...map.values()].sort((a, b) => (a[0].eta_seconds ?? 1e12) - (b[0].eta_seconds ?? 1e12));
}

function renderRoute(group) {
  const head = group[0];
  const items = group.slice(0, MAX_NEXT + 1);
  const main = items[0];
  const rest = items.slice(1);
  const u = urgencyInfo(main.eta_seconds);
  const hint = u.hint ? `<span class="hint ${u.cls}">${u.hint}</span>` : '';
  return `
    <div class="route-block">
      <div class="route-head">
        <span class="route">${head.route || '—'}</span>
        <span class="dir">${head.direction || ''}</span>
      </div>
      <div class="eta-main">
        <span class="eta ${u.cls}">${main.eta_local || main.eta_text || ''}</span>
        ${rest.map(a => `<span class="eta next">${a.eta_local || a.eta_text || ''}</span>`).join('')}
      </div>
      ${hint}
    </div>`;
}

async function load() {
  btn.disabled = true;
  const oldText = btn.textContent;
  btn.textContent = '…';
  try {
    const url = '/arrivals/' + STOP_ID + (ROUTES ? '?routes=' + encodeURIComponent(ROUTES) : '');
    const r = await fetch(url, { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    if (data.name) titleEl.textContent = data.name;
    if (data.error) {
      root.innerHTML = '<div class="stale">данные временно недоступны<br>попробуй ещё раз через минуту</div>';
    } else {
      const arrivals = data.arrivals || [];
      if (!arrivals.length) {
        root.innerHTML = '<div class="empty">прибытий нет</div>';
      } else {
        const groups = groupByRoute(arrivals);
        root.innerHTML = groups.map(renderRoute).join('');
      }
    }
    const now = new Date();
    footer.textContent = 'обновлено ' + now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  } catch (e) {
    root.innerHTML = '<div class="stale">данные недоступны: ' + e.message + '</div>';
    footer.textContent = '—';
  } finally {
    btn.disabled = false;
    btn.textContent = oldText;
  }
}

btn.addEventListener('click', load);
load();
</script>
</body>
</html>
"""


@app.get("/stop/{stop_id}", response_class=HTMLResponse)
async def stop_page(stop_id: str) -> HTMLResponse:
    canonical = yandex.normalize_stop_id(stop_id)
    name = ""
    routes_filter: list[str] = []
    for s in storage.list_stops():
        if s.stop_id == canonical:
            name = s.name
            routes_filter = s.routes
            break
    title = name or canonical
    routes_csv = ",".join(routes_filter)
    html = (
        _STOP_HTML.replace("__STOP_ID__", canonical)
        .replace("__ROUTES__", routes_csv)
        .replace("__TITLE__", title)
    )
    return HTMLResponse(html)


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
    stops = storage.list_stops()
    if not stops:
        return []

    async def _fetch(stop: Stop) -> StopArrivals:
        try:
            payload = await masstransit.get_stop_state(stop.stop_id)
        except yandex.YandexError as e:
            return StopArrivals(
                stop_id=stop.stop_id,
                name=stop.name,
                arrivals=[],
                fetched_at=_now_iso(),
                error=str(e),
            )
        name, arrivals = yandex.parse_arrivals(payload)
        return StopArrivals(
            stop_id=stop.stop_id,
            name=stop.name or name,
            arrivals=_filter(arrivals, stop.routes),
            fetched_at=_now_iso(),
        )

    return list(await asyncio.gather(*(_fetch(s) for s in stops)))


@app.get("/arrivals/{stop_id}", dependencies=[Depends(require_api_key)])
async def stop_arrivals_endpoint(
    stop_id: str,
    routes: str | None = Query(default=None, description="CSV маршрутов: 925,907"),
) -> StopArrivals:
    assert masstransit is not None
    try:
        payload = await masstransit.get_stop_state(stop_id)
    except yandex.YandexError as e:
        return StopArrivals(
            stop_id=stop_id,
            name="",
            arrivals=[],
            fetched_at=_now_iso(),
            error=str(e),
        )
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


@app.get("/yc_proxy.py", response_class=PlainTextResponse)
async def yc_proxy_code() -> PlainTextResponse:
    """Код функции для деплоя на Yandex.Cloud Functions. Открой на телефоне,
    выдели всё, скопируй и вставь в редактор Я.Облака."""
    return PlainTextResponse(
        _YC_PROXY_PATH.read_text(encoding="utf-8"),
        media_type="text/plain; charset=utf-8",
    )


def _require_mos() -> mosgortrans.MosClient:
    if mos_client is None:
        raise HTTPException(
            status_code=503,
            detail="MOS_API_KEY не задан в Railway Variables",
        )
    return mos_client


@app.get("/mos/datasets", dependencies=[Depends(require_api_key)])
async def mos_datasets_endpoint(
    q: str = Query(default="", description="Подстрока в названии датасета"),
    limit: int = Query(default=30, ge=1, le=200),
) -> JSONResponse:
    """Каталог датасетов data.mos.ru. Без q возвращает первые N (всего ~5000),
    с q фильтрует по подстроке в Caption."""
    cli = _require_mos()
    catalog = await cli.list_datasets()
    if not isinstance(catalog, list):
        return JSONResponse({"error": "ожидался список", "raw_type": type(catalog).__name__, "preview": str(catalog)[:500]})
    if q:
        results = mosgortrans.search_datasets_by_name(catalog, q)
        return JSONResponse({"total_in_catalog": len(catalog), "matched": len(results), "results": results[:limit]})
    return JSONResponse(
        {
            "total": len(catalog),
            "first_n": [
                {
                    "id": ds.get("Id") or ds.get("id"),
                    "caption": ds.get("Caption") or ds.get("name"),
                }
                for ds in catalog[:limit] if isinstance(ds, dict)
            ],
        }
    )


@app.get("/mos/dataset/{dataset_id}", dependencies=[Depends(require_api_key)])
async def mos_dataset_meta_endpoint(dataset_id: int) -> JSONResponse:
    """Метаданные одного датасета — название, описание, схема полей."""
    return JSONResponse(await _require_mos().dataset_meta(dataset_id))


@app.get("/mos/sample/{dataset_id}", dependencies=[Depends(require_api_key)])
async def mos_dataset_sample_endpoint(
    dataset_id: int,
    top: int = Query(default=3, ge=1, le=20),
    odata: str | None = Query(default=None, description="Опциональный $filter (OData)"),
) -> JSONResponse:
    """Несколько строк датасета — посмотреть какие поля и значения."""
    return JSONResponse(
        await _require_mos().dataset_rows(dataset_id, odata_filter=odata, top=top)
    )


@app.get("/mos/probe", dependencies=[Depends(require_api_key)])
async def mos_probe_endpoint() -> JSONResponse:
    """Делает несколько диагностических запросов чтобы понять что доступно.
    Любая ошибка — в JSON, не 500."""
    out: dict[str, Any] = {
        "key_set": bool(settings.mos_api_key),
        "key_len": len(settings.mos_api_key),
        "client_initialized": mos_client is not None,
    }
    if mos_client is None:
        out["error"] = "MOS_API_KEY не задан или сервис не передеплоился"
        return JSONResponse(out, status_code=200)

    # 1) Маленькая проверка — count одного датасета
    try:
        out["count_622"] = await mos_client.dataset_count(622)
    except Exception as e:
        out["count_622_error"] = f"{type(e).__name__}: {e}"

    # 2) Каталог
    try:
        catalog = await mos_client.list_datasets()
        if isinstance(catalog, list):
            out["catalog_size"] = len(catalog)
            queries = ["прогноз прибытия", "остановки наземного", "маршруты наземного"]
            out["matches"] = {
                q: mosgortrans.search_datasets_by_name(catalog, q)[:5] for q in queries
            }
        else:
            out["catalog_type"] = type(catalog).__name__
            out["catalog_preview"] = str(catalog)[:500]
    except Exception as e:
        out["catalog_error"] = f"{type(e).__name__}: {e}"

    return JSONResponse(out, status_code=200)
