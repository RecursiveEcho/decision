#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
name="decision-backend-test-$$"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Disposable synthetic database; never uses root .env or an existing project database.
docker run --rm -d --name "$name" -e POSTGRES_DB=decision_backend_test -e POSTGRES_USER=decision_test -e POSTGRES_PASSWORD=synthetic-test-only -p 127.0.0.1::5432 postgres:16 >/dev/null
for i in $(seq 1 30); do
  if docker exec "$name" pg_isready -h 127.0.0.1 -U decision_test -d decision_backend_test >/dev/null 2>&1; then break; fi
  sleep 1
done
port=$(docker port "$name" 5432/tcp | sed 's/.*://')
export TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${port}/decision_backend_test"
mkdir -p target
if ./mvnw -B test >target/test-run.log 2>&1; then
  tail -15 target/test-run.log
else
  tail -25 target/test-run.log
  exit 1
fi
