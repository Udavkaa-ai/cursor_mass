"""Прокси-функция для Yandex Cloud Functions.

Деплоится в Yandex.Cloud, отдаёт публичный URL. Наш сервис на Railway
ходит через этот URL к apidata.mos.ru, чтобы обойти geo-блокировку.

Контракт:
  GET <function-url>?_path=/v1/datasets/622/count&api_key=...
  → проксирует на https://apidata.mos.ru/v1/datasets/622/count?api_key=...
"""

import json
import urllib.error
import urllib.parse
import urllib.request

ALLOWED_HOSTS = ("apidata.mos.ru", "data.mos.ru")


def handler(event, context):
    params = dict(event.get("queryStringParameters") or {})
    path = params.pop("_path", "/")
    host = params.pop("_host", "apidata.mos.ru")

    if host not in ALLOWED_HOSTS:
        return {"statusCode": 400, "body": json.dumps({"error": f"host {host} not allowed"})}
    if not path.startswith("/"):
        path = "/" + path

    url = f"https://{host}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params, doseq=True)

    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "yc-proxy/1.0",
        },
    )

    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read()
            return {
                "statusCode": r.status,
                "headers": {
                    "Content-Type": r.headers.get("Content-Type", "application/json"),
                    "Cache-Control": "no-store",
                },
                "body": body.decode("utf-8", errors="replace"),
                "isBase64Encoded": False,
            }
    except urllib.error.HTTPError as e:
        return {
            "statusCode": e.code,
            "headers": {"Content-Type": e.headers.get("Content-Type", "text/plain")},
            "body": e.read().decode("utf-8", errors="replace"),
        }
    except Exception as e:
        return {
            "statusCode": 502,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps(
                {"proxy_error": str(e), "target": url}, ensure_ascii=False
            ),
        }
