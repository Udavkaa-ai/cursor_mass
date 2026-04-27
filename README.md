# Bus Arrival Tracker

Микросервис для быстрого получения прогнозов прибытия общественного транспорта.
Источник данных — неофициальный masstransit API Яндекс.Карт. Один HTTP-запрос
по сохранённым остановкам и сразу видишь, когда придёт нужный автобус, без
открытия Яндекс.Карт / 2ГИС и кликов по карте.

Сервис полностью стейтлес кроме SQLite-файла со списком остановок и
маршрутов-фильтров. Готов к деплою на Railway 24/7. На будущее задумано как
бэкенд для Android-виджета.

## Endpoints

Авторизация опциональна. Если переменная `API_KEY` пустая (по умолчанию) —
ручки открыты без заголовка. Если `API_KEY` задан — все ручки кроме `/health`
требуют заголовок `X-API-Key: <тот же ключ>`. Включай, когда соберёшься
открывать сервис кому-то ещё или повесишь публичный URL.

| Метод  | Путь                  | Что делает                                                              |
| ------ | --------------------- | ----------------------------------------------------------------------- |
| GET    | `/health`             | пинг                                                                    |
| GET    | `/stops`              | список сохранённых остановок                                            |
| POST   | `/stops`              | добавить/обновить остановку (`{stop_id, name, routes}`)                  |
| DELETE | `/stops/{stop_id}`    | удалить                                                                  |
| GET    | `/arrivals`           | прогнозы по всем сохранённым остановкам, отфильтрованные по `routes`     |
| GET    | `/arrivals/{stop_id}` | прогнозы по конкретной остановке, фильтр через `?routes=925,907`         |
| GET    | `/raw/{stop_id}`      | сырой ответ Яндекса (отладка)                                            |
| GET    | `/search?q=...`       | сырой поиск Яндекса (помощь в нахождении stop_id)                        |

## Как достать `stop_id`

Самый надёжный способ — через URL Яндекс.Карт в браузере:

1. Открой [yandex.ru/maps](https://yandex.ru/maps/) и найди свою остановку.
2. Кликни по знаку остановки на карте — слева откроется карточка с маршрутами.
3. В адресной строке появится фрагмент вида `/stops/stop__9645524/`.
   Вот это `stop__9645524` и есть `stop_id`.

Альтернатива через сервис: `GET /search?q=Дорский Ручей` — вернёт сырой JSON
от Яндекса, в нём ищи `"id":"stop__..."`.

## Локальный запуск

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Затем (без `API_KEY` — auth выключена):

```bash
curl -s localhost:8000/health
# {"ok": true}

# добавить остановку
curl -s -X POST localhost:8000/stops -H "Content-Type: application/json" \
  -d '{"stop_id":"stop__9645524","name":"Улица Дорский Ручей","routes":["925"]}'

# прогноз по всем сохранённым
curl -s localhost:8000/arrivals
```

## Деплой на Railway

1. Зайди в Railway → New Project → Deploy from GitHub repo, выбери репозиторий
   и ветку `claude/bus-arrival-tracker-rcZgg` (или main после мержа).
2. Railway автоматически подхватит `requirements.txt` и `Procfile`.
3. В Variables добавь:
   - `DATABASE_PATH` — `/data/db.sqlite3`
   - `API_KEY` — опционально, для защиты публичного URL. Сгенерировать:
     `openssl rand -hex 32`. Пусто = без авторизации.
4. В разделе Volumes создай volume и подключи к `/data`, чтобы SQLite не
   терялся при редеплое.
5. Открой публичный URL, дёрни `/health`, потом наполни `/stops`.

Если предпочитаешь Docker — на Railway можно собрать без него: nixpacks сам
определит Python-проект.

## Пример реального запроса

```bash
curl -s 'https://your-app.up.railway.app/arrivals/stop__9645524?routes=925'
# Если включал API_KEY — добавь -H "X-API-Key: <ключ>"
```

Ответ:

```json
{
  "stop_id": "stop__9645524",
  "name": "Улица Дорский Ручей",
  "arrivals": [
    {"route": "925", "type": "bus", "direction": "МЦД Остафьево", "eta_text": "5 мин", "eta_seconds": 300}
  ],
  "fetched_at": "2026-04-27T20:15:00+00:00"
}
```

## Что дальше

- Android-виджет (Kotlin + Jetpack Glance), бьющий в `/arrivals` каждые N минут.
- Добавить второго провайдера (data.mos.ru / Мосгортранс) как fallback.
- Кеш ответов Яндекса на 10–20 секунд, чтобы не словить rate limit при частых
  обновлениях виджета.

## Структура

```
app/
  main.py        FastAPI-приложение и роуты
  yandex.py      клиент к masstransit API + парсер
  storage.py     SQLite CRUD для остановок
  models.py      pydantic-модели
  config.py      env-переменные
requirements.txt
Procfile         railway/procfile-style: запуск uvicorn
.env.example
```
