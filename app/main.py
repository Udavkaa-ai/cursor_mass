from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.responses import JSONResponse

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


@app.get("/stops", dependencies=[Depends(require_api_key)])
async def list_stops_endpoint() -> list[Stop]:
    return storage.list_stops()


@app.post(
    "/stops",
    dependencies=[Depends(require_api_key)],
    status_code=201,
)
async def add_stop_endpoint(payload: StopCreate) -> Stop:
    return storage.upsert_stop(payload)


@app.delete(
    "/stops/{stop_id}",
    dependencies=[Depends(require_api_key)],
    status_code=204,
    response_class=Response,
)
async def delete_stop_endpoint(stop_id: str) -> Response:
    if not storage.delete_stop(stop_id):
        raise HTTPException(status_code=404, detail="stop not found")
    return Response(status_code=204)


@app.get("/arrivals", dependencies=[Depends(require_api_key)])
async def all_arrivals_endpoint() -> list[StopArrivals]:
    assert masstransit is not None
    out: list[StopArrivals] = []
    for s in storage.list_stops():
        try:
            payload = await masstransit.get_stop_info(s.stop_id)
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
    payload = await masstransit.get_stop_info(stop_id)
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
    """Сырой ответ Яндекса для отладки парсера."""
    assert masstransit is not None
    payload = await masstransit.get_stop_info(stop_id)
    return JSONResponse(payload)


@app.get("/search", dependencies=[Depends(require_api_key)])
async def search_endpoint(q: str = Query(..., min_length=2)) -> JSONResponse:
    """Поиск по Яндексу. Используй чтобы вытащить stop_id по названию."""
    assert masstransit is not None
    payload = await masstransit.search(q)
    return JSONResponse(payload)
