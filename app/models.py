from __future__ import annotations

from pydantic import BaseModel, Field


class StopCreate(BaseModel):
    stop_id: str = Field(..., description="Идентификатор остановки Яндекс.Карт, например stop__9645524")
    name: str = Field(..., description="Произвольное человекочитаемое имя")
    routes: list[str] = Field(default_factory=list, description="Фильтр маршрутов: ['925','907']. Пусто = все")


class Stop(StopCreate):
    id: int


class Arrival(BaseModel):
    route: str
    type: str = ""
    direction: str | None = None
    eta_text: str
    eta_seconds: int | None = None
    eta_local: str = ""  # человекочитаемое: "через 5 мин", "через 1ч 12м", "12:31"


class StopArrivals(BaseModel):
    stop_id: str
    name: str
    arrivals: list[Arrival]
    fetched_at: str
    error: str | None = None
