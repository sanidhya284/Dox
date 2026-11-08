#!/usr/bin/env bash
# Restore DRILL (ADR-0011, runbook section 8.1): restores a backup into a throwaway Postgres container and
# prints row counts. It never touches the live database.
#   /opt/chat/restore.sh /opt/chat/backups/chat-<ts>.dump
# Restoring the LIVE database is a manual, rare operation: see the runbook ("Restore the live database").
set -euo pipefail
DUMP="${1:?usage: restore.sh <file.dump>}"
NAME="chat-restore-drill-$$"
cleanup() { docker rm -f "$NAME" > /dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" -e POSTGRES_PASSWORD=drill-only postgres:16.4-alpine > /dev/null
for _ in $(seq 1 30); do
  docker exec "$NAME" pg_isready -U postgres > /dev/null 2>&1 && break
  sleep 1
done
docker exec "$NAME" createdb -U postgres chat
docker cp "$DUMP" "$NAME:/tmp/restore.dump"
docker exec "$NAME" pg_restore -U postgres -d chat --no-owner --exit-on-error /tmp/restore.dump
echo "restored $(basename "$DUMP") into a scratch container; row counts:"
for t in users conversations conversation_participants messages flyway_schema_history; do
  printf '  %-26s %s\n' "$t" "$(docker exec "$NAME" psql -U postgres -d chat -tAc "SELECT count(*) FROM $t")"
done
echo "RESTORE DRILL OK (scratch container removed on exit)"
