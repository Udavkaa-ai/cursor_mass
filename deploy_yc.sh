#!/usr/bin/env bash
# Деплой в Yandex.Cloud Serverless Containers.
# Запускать на ПК с установленными: yc CLI, Docker Desktop, jq.
# Перед первым запуском: `yc init`.

set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-bus-tracker}"
REGISTRY_NAME="${REGISTRY_NAME:-bus-tracker}"
SA_NAME="${SA_NAME:-bus-tracker-sa}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

FOLDER_ID="$(yc config get folder-id)"
echo "→ folder-id = ${FOLDER_ID}"

# 1. Container Registry (создаём если нет)
if ! yc container registry get --name "$REGISTRY_NAME" >/dev/null 2>&1; then
  echo "→ создаю registry $REGISTRY_NAME"
  yc container registry create --name "$REGISTRY_NAME"
fi
REG_ID="$(yc container registry get --name "$REGISTRY_NAME" --format json | jq -r .id)"
echo "→ registry-id = ${REG_ID}"

# 2. Service Account для пулла образа
if ! yc iam service-account get --name "$SA_NAME" >/dev/null 2>&1; then
  echo "→ создаю service account $SA_NAME"
  yc iam service-account create --name "$SA_NAME"
fi
SA_ID="$(yc iam service-account get --name "$SA_NAME" --format json | jq -r .id)"
echo "→ sa-id = ${SA_ID}"

echo "→ выдаю SA права container-registry.images.puller"
yc resource-manager folder add-access-binding "$FOLDER_ID" \
  --role container-registry.images.puller \
  --subject "serviceAccount:${SA_ID}" >/dev/null 2>&1 || true

# 3. Логин Docker в Y.Cloud Container Registry
echo "→ docker login в cr.yandex"
yc container registry configure-docker

IMAGE="cr.yandex/${REG_ID}/${CONTAINER_NAME}:${IMAGE_TAG}"

# 4. Сборка и пуш образа
echo "→ docker build $IMAGE"
docker build -t "$IMAGE" .
echo "→ docker push $IMAGE"
docker push "$IMAGE"

# 5. Serverless Container (создаём если нет)
if ! yc serverless container get --name "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "→ создаю serverless container $CONTAINER_NAME"
  yc serverless container create --name "$CONTAINER_NAME"
fi

# 6. Публичный доступ
echo "→ разрешаю публичные вызовы"
yc serverless container allow-unauthenticated-invoke --name "$CONTAINER_NAME"

# 7. Деплой ревизии
# Переменные окружения берём из env.yc (если есть) или просим пользователя задать вручную
ENV_FILE="env.yc"
ENV_ARGS=()
if [ -f "$ENV_FILE" ]; then
  echo "→ применяю переменные из $ENV_FILE"
  while IFS='=' read -r key val; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    # Удаляем CR (если файл сохранён в Windows-кодировке)
    val="${val%$'\r'}"
    if [[ "$val" == *,* ]]; then
      # yc --environment разделяет пары запятой → base64-кодим, в приложении
      # config._maybe_b64 распакует {name}_B64
      b64="$(printf '%s' "$val" | base64 -w0 2>/dev/null || printf '%s' "$val" | base64)"
      b64="${b64//$'\n'/}"
      echo "   ${key}: содержит запятые → отправляю как ${key}_B64 (base64)"
      ENV_ARGS+=(--environment "${key}_B64=${b64}")
    else
      ENV_ARGS+=(--environment "${key}=${val}")
    fi
  done < "$ENV_FILE"
else
  echo "⚠ нет env.yc — деплой без STOPS! Создай env.yc в формате KEY=value"
fi

echo "→ деплой revision"
yc serverless container revision deploy \
  --container-name "$CONTAINER_NAME" \
  --image "$IMAGE" \
  --service-account-id "$SA_ID" \
  --memory 256MB \
  --cores 1 \
  --execution-timeout 30s \
  --concurrency 4 \
  "${ENV_ARGS[@]}"

URL="$(yc serverless container get --name "$CONTAINER_NAME" --format json | jq -r .url)"
echo ""
echo "✅ Готово. Публичный URL:"
echo "   ${URL}"
echo ""
echo "Проверка:"
echo "   curl ${URL}/health"
echo "   ${URL}/stop/5854295457"
