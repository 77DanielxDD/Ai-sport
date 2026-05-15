#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/deploy/cloud/.env"

cd "${REPO_ROOT}"
docker compose --env-file "${ENV_FILE}" -f docker-compose.cloud.yml down

echo "Cloud stack stopped."

