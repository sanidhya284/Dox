#!/usr/bin/env bash
# Daily database backup (ADR-0011), run by the deploy user's crontab (installed by the deploy job):
#   15 3 * * * /opt/chat/backup.sh >> /opt/chat/backups/backup.log 2>&1
# Writes a custom-format pg_dump and keeps the newest 7. Backups on the same server don't survive losing the
# server: copy them off AWS regularly (runbook section 8.3).
set -euo pipefail
DIR="${CHAT_DIR:-/opt/chat}"
KEEP="${KEEP:-7}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
out="$DIR/backups/chat-$ts.dump"

docker compose -f "$DIR/compose.yml" exec -T db pg_dump -U chat -d chat -Fc > "$out.tmp"
mv "$out.tmp" "$out"
chmod 600 "$out"
ls -1t "$DIR"/backups/chat-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm --
echo "$(date -u +%FT%TZ) backup ok: $(basename "$out") ($(du -h "$out" | cut -f1)); kept $(ls -1 "$DIR"/backups/chat-*.dump | wc -l)"
