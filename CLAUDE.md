# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Local development
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload          # dev server at localhost:8000

# Deploy to Yandex Cloud (requires yc CLI, Docker, jq)
cp env.yc.example env.yc              # fill in STOPS and optional API_KEY
bash deploy_yc.sh
```

No test suite exists yet.

## Architecture

FastAPI app (`app/`) that scrapes Yandex Maps HTML pages to extract real-time bus arrival predictions. No official API — data comes from parsing embedded SSR state out of `<script class="state-view">` tags.

**Data flow for `/arrivals/{stop_id}`:**
1. `main.py` calls `yandex.YandexMasstransit.get_stop_state(stop_id)`
2. `yandex.py` fetches `https://yandex.ru/maps/213/moscow/stops/{id}/` with a browser-like User-Agent
3. `_extract_state()` finds the JSON blob in one of several `<script>` patterns (`_STATE_PATTERNS`)
4. `parse_arrivals()` navigates `state["stack"][0]["stops"]["data"]["transports"]` to build `Arrival` objects
5. Results are cached 45 seconds per stop (`STATE_CACHE_TTL_SECONDS`) to avoid anti-bot throttling

**Stop storage — two modes (mutually exclusive):**
- **env-mode** (recommended): `STOPS` env var holds a JSON array of stops. `POST/DELETE /stops` raise `ReadOnlyError 409`. Survives redeployment without a volume.
- **SQLite-mode**: if `STOPS` is unset, stops live in SQLite at `DATABASE_PATH` (default `/tmp/data/db.sqlite3`). Requires a persistent volume in production.

**Key files:**
- `app/yandex.py` — scraper + parser; most fragile part (Yandex anti-bot, HTML structure changes)
- `app/main.py` — all FastAPI routes + inline HTML for `/` and `/stop/{id}` pages
- `app/storage.py` — stop CRUD, mode detection via `env_mode()`
- `app/visits.py` — async YDB visit counter + hourly buckets for the sparkline on stop pages; gracefully returns 0 if `YDB_ENDPOINT` is unset
- `proxy/yc_function.py` — standalone Yandex Cloud Function code, proxies requests to `apidata.mos.ru` (only used if `MOS_PROXY_URL` is set)

**stop_id formats:** Yandex uses both numeric (`5854295457`) and canonical (`stop__5854295457`). `normalize_stop_id()` in `yandex.py` always converts to canonical. Pass either format to any endpoint.

**Anti-bot notes:** The scraper breaks when Yandex returns a challenge page instead of real HTML. Symptom: `YandexError("Не нашёл встроенный state в HTML")`, frontend shows "данные временно недоступны". Debug via `/raw_html/{stop_id}` (returns first 50 KB of HTML) and `/state/{stop_id}` (navigates the parsed state tree).

**Deployment:** Yandex Cloud Serverless Containers. `deploy_yc.sh` builds Docker image, pushes to Y.Cloud Container Registry, and deploys a revision. Values with commas in `env.yc` (like JSON arrays) are automatically base64-encoded and passed as `KEY_B64`; `config.py` decodes them transparently.
