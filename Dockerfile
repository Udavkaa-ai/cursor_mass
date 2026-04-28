FROM mirror.gcr.io/library/python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DATABASE_PATH=/tmp/data/db.sqlite3

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app/ ./app/
COPY proxy/ ./proxy/

EXPOSE 8080

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
