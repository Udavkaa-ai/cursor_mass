import base64
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_key: str
    database_path: str
    yandex_lang: str
    request_timeout: float
    stops_env: str
    mos_api_key: str
    mos_proxy_url: str


def _maybe_b64(name: str) -> str:
    """Возвращает значение переменной окружения NAME, либо base64-декод NAME_B64.

    Нужно чтобы обходить разделитель-запятую в yc CLI --environment."""
    raw = os.environ.get(name, "").strip()
    if raw:
        return raw
    b64 = os.environ.get(f"{name}_B64", "").strip()
    if not b64:
        return ""
    try:
        return base64.b64decode(b64).decode("utf-8")
    except Exception:
        return ""


def _load() -> Settings:
    return Settings(
        api_key=_maybe_b64("API_KEY"),
        database_path=os.environ.get("DATABASE_PATH", "./data/db.sqlite3"),
        yandex_lang=os.environ.get("YANDEX_LANG", "ru_RU"),
        request_timeout=float(os.environ.get("REQUEST_TIMEOUT", "10")),
        stops_env=_maybe_b64("STOPS"),
        mos_api_key=_maybe_b64("MOS_API_KEY"),
        mos_proxy_url=os.environ.get("MOS_PROXY_URL", "").strip(),
    )


settings = _load()
