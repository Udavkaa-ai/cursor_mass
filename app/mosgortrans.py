"""Клиент к apidata.mos.ru — открытому API Правительства Москвы.

Цель — выяснить что доступно по нашему API-ключу: каталог датасетов,
схемы, образцы строк, и есть ли вообще real-time прогнозы прибытия.
"""

from __future__ import annotations

from typing import Any

import httpx

from .config import settings

API_BASE = "https://apidata.mos.ru/v1"


class MosError(RuntimeError):
    pass


class MosClient:
    def __init__(self) -> None:
        if not settings.mos_api_key:
            raise MosError(
                "MOS_API_KEY не задан. Получить ключ на https://apidata.mos.ru"
            )
        self._client = httpx.AsyncClient(
            timeout=settings.request_timeout,
            follow_redirects=True,
            headers={"Accept": "application/json"},
        )
        self._key = settings.mos_api_key

    async def close(self) -> None:
        await self._client.aclose()

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        full_params = {**(params or {}), "api_key": self._key}
        url = f"{API_BASE}{path}"
        r = await self._client.get(url, params=full_params)
        if r.status_code >= 400:
            raise MosError(f"{r.status_code} {url}: {r.text[:400]}")
        try:
            return r.json()
        except ValueError as e:
            raise MosError(f"Ответ не JSON: {r.text[:300]}") from e

    async def list_datasets(self) -> Any:
        """Каталог всех датасетов. Возвращает большой массив."""
        return await self._get("/datasets")

    async def dataset_meta(self, dataset_id: int) -> Any:
        return await self._get(f"/datasets/{dataset_id}")

    async def dataset_count(self, dataset_id: int) -> Any:
        return await self._get(f"/datasets/{dataset_id}/count")

    async def dataset_rows(
        self,
        dataset_id: int,
        odata_filter: str | None = None,
        top: int = 10,
        skip: int = 0,
    ) -> Any:
        params: dict[str, Any] = {"$top": top, "$skip": skip}
        if odata_filter:
            params["$filter"] = odata_filter
        return await self._get(f"/datasets/{dataset_id}/rows", params)


def search_datasets_by_name(catalog: list[dict[str, Any]], query: str) -> list[dict[str, Any]]:
    """Фильтрует список датасетов по подстроке в Caption."""
    q = query.lower().strip()
    if not q:
        return []
    out = []
    for ds in catalog:
        if not isinstance(ds, dict):
            continue
        caption = str(ds.get("Caption") or ds.get("name") or "").lower()
        if q in caption:
            out.append(
                {
                    "id": ds.get("Id") or ds.get("id"),
                    "caption": ds.get("Caption") or ds.get("name"),
                    "rows": ds.get("ItemsCount") or ds.get("rowsCount"),
                    "updated": ds.get("DataDate") or ds.get("dataDate"),
                }
            )
    return out
