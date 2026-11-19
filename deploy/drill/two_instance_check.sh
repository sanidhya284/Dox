#!/usr/bin/env bash
# Two-instance deploy check (ADR-0013; CI job "two-instances", and locally). From an empty server directory:
# deploy <tag1> on the two-instance path (a first deploy), then roll to <tag2> while the zero-failure probe runs.
# Fails unless the probe saw 0 failed requests, every acked send is in Postgres and its socket client converged.
#   deploy/drill/two_instance_check.sh <tag1> <tag2> [server-dir]
# Images: chat-app:<tag> and chat-web:<tag> (local, not pulled). Needs ports 80/443 free.
set -euo pipefail

R="$(cd "$(dirname "$0")/.." && pwd)"
T1="${1:?tag1}"; T2="${2:?tag2}"; SRV="${3:-$(mktemp -d)}"
mkdir -p "$SRV/caddy"
cp "$R/compose.yml" "$R/compose.phase4.yml" "$R/prod/deploy.sh" "$R/prod/postdeploy_check.py" "$SRV/"
cp "$R/caddy/Caddyfile" "$SRV/caddy/"
cp "$R/.env.local.example" "$SRV/.env"
echo "CHECK_INSECURE_TLS=1" >> "$SRV/.env"
chmod +x "$SRV/deploy.sh"
export CHAT_DIR="$SRV" SKIP_PULL=1

echo "== first deploy (two instances): $T1"
"$SRV/deploy.sh" "$T1"

rm -f "$SRV/stop"
python3 "$R/drill/zero_failure_probe.py" --env "$SRV/.env" --base https://localhost --insecure \
  --stop-file "$SRV/stop" --json-out "$SRV/probe.json" \
  --psql "docker compose -f $SRV/compose.yml -f $SRV/compose.phase4.yml exec -T db psql -U chat -d chat -tA -c" &
PROBE=$!
sleep 8
echo "== rolling deploy under the probe: $T1 -> $T2"
"$SRV/deploy.sh" "$T2"
sleep 5
touch "$SRV/stop"
if wait "$PROBE"; then
  echo "== TWO-INSTANCE CHECK PASSED"
else
  echo "== TWO-INSTANCE CHECK FAILED (probe)"
  exit 1
fi
