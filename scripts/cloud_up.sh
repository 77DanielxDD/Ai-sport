#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/deploy/cloud/.env"
WITH_AI="${WITH_AI:-false}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}"
  echo "Run: cp deploy/cloud/.env.example deploy/cloud/.env && edit values"
  exit 1
fi

cd "${REPO_ROOT}"
if [[ "${WITH_AI}" == "true" ]]; then
  docker compose --profile with-ai --env-file "${ENV_FILE}" -f docker-compose.cloud.yml up -d --build
else
  docker compose --env-file "${ENV_FILE}" -f docker-compose.cloud.yml up -d --build
fi

echo
echo "Cloud stack is starting..."
echo "Gateway: http://<server-ip>/"
echo "Health:  http://<server-ip>/api/system/health"
