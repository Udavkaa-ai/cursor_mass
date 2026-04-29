# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Backend (FastAPI)

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload          # dev server at localhost:8000

# Deploy to Yandex Cloud (requires yc CLI, Docker, jq)
cp env.yc.example env.yc              # fill in STOPS and optional API_KEY
bash deploy_yc.sh
```

### Android APK

```bash
cd android_apk
./gradlew assembleDebug                # build debug APK → app/build/outputs/apk/debug/
./gradlew installDebug                 # build and install on connected device/emulator
```

The server URL and timing constants are hardcoded in `app/src/main/kotlin/ru/buswidget/data/Config.kt` — update `SERVER_URL` before building for a different backend.

No test suite exists for either component.

## Architecture

Two independent sub-projects sharing the same repository:

- **`app/`** — Python FastAPI backend, deployed to Yandex Cloud Serverless Containers
- **`android_apk/`** — Kotlin Android app (minSdk 24, targetSdk 34) with home-screen widget

---

### Backend (`app/`)

FastAPI app that scrapes Yandex Maps HTML pages to extract real-time bus arrival predictions. No official API — data comes from parsing embedded SSR state out of `<script class="state-view">` tags.

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
- `app/visits.py` — visit counter stub (disabled; returns zeros)
- `proxy/yc_function.py` — standalone Yandex Cloud Function that proxies requests to `apidata.mos.ru` (only used if `MOS_PROXY_URL` is set)

**stop_id formats:** Yandex uses both numeric (`5854295457`) and canonical (`stop__5854295457`). `normalize_stop_id()` in `yandex.py` always converts to canonical. Pass either format to any endpoint.

**Anti-bot notes:** The scraper breaks when Yandex returns a challenge page instead of real HTML. Symptom: `YandexError("Не нашёл встроенный state в HTML")`, frontend shows "данные временно недоступны". Debug via `/raw_html/{stop_id}` (returns first 50 KB of HTML) and `/state/{stop_id}` (navigates the parsed state tree).

**Deployment:** `deploy_yc.sh` builds a Docker image, pushes to Y.Cloud Container Registry, and deploys a revision. Values with commas in `env.yc` (like JSON arrays) are automatically base64-encoded and passed as `KEY_B64`; `config.py` decodes them transparently.

---

### Android App (`android_apk/`)

**Package:** `ru.buswidget`

**Component overview:**

| Class | Role |
|---|---|
| `MainActivity` | Stop list; launches `AddStopActivity` and `ArrivalsActivity` |
| `AddStopActivity` | Add/edit a stop; can launch `MapPickerActivity` |
| `MapPickerActivity` | WebView loading `yandex.ru/maps`; intercepts stop URLs to extract stop ID |
| `ArrivalsActivity` | Full-screen arrival board with live ETA countdown |
| `widget/BusWidgetProvider` | Home-screen widget; handles START/STOP broadcast actions |
| `widget/PollService` | Foreground service; drives per-widget polling sessions |
| `data/StopStorage` | Stop CRUD in SharedPreferences as JSON |
| `data/Config` | Hardcoded `SERVER_URL`, `SESSION_SEC` (300), `POLL_SEC` (15) |

**Widget data flow:**
1. User taps START → `BusWidgetProvider.onReceive` → `PollService.startFor()`
2. `PollService` runs a 1-second tick loop; polls `GET /arrivals/{stopId}` every `POLL_SEC`
3. API response is stored as a `Snapshot(fetchedAt, arrivals)`
4. Each tick calls `liveArrivals()` which recomputes `liveSecs = etaSeconds - elapsed` for smooth countdown
5. `BusWidgetProvider.updateActive()` renders up to 3 arrival rows via `RemoteViews`

**Snapshot update rule:** `PollService.fetchAndUpdate` only replaces the snapshot when arrivals differ from the previous snapshot. This prevents the live countdown from restarting every 15 seconds when the server returns identical cached data (server TTL is 45s, Android polls every 15s).

**MapPickerActivity WebView:** Blocks all non-`http(s)` URL schemes in `shouldOverrideUrlLoading` to prevent Yandex Maps' `intent://` redirects from crashing the WebView. Stop selection is detected via URL pattern `stops/(?:stop__)?(\d+)` in `doUpdateVisitedHistory`.

**ETA color scheme** (used in both `ArrivalAdapter` and `PollService`):
- `null` / unknown → grey
- ≤ 0 s → red (arriving now)
- ≤ 180 s → red
- ≤ 300 s → dark orange
- ≤ 420 s (widget) / else (activity) → orange/white
