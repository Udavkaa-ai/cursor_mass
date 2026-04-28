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


def _load() -> Settings:
    return Settings(
        api_key=os.environ.get("API_KEY", ""),
        database_path=os.environ.get("DATABASE_PATH", "./data/db.sqlite3"),
        yandex_lang=os.environ.get("YANDEX_LANG", "ru_RU"),
        request_timeout=float(os.environ.get("REQUEST_TIMEOUT", "10")),
        stops_env=os.environ.get("STOPS", ""),
        mos_api_key=os.environ.get("MOS_API_KEY", ""),
    )


settings = _load()
