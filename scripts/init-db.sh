#!/usr/bin/env bash
# Apply the koshei schema (app/src/main/resources/schema.sql) into the compose Postgres.
# Run after `docker compose up -d` (and once the postgres healthcheck is green).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[init-db] applying app/src/main/resources/schema.sql into compose postgres"
docker compose exec -T postgres psql -U koshei -d koshei < app/src/main/resources/schema.sql
echo "[init-db] done"
