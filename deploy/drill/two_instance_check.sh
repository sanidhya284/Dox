#!/usr/bin/env bash
# Two-instance deploy check (ADR-0013; CI job "two-instances", and locally). From an empty server directory:
# 1. deploy <tag1> on the two-instance path (a first deploy);
# 2. roll to <tag2> while the zero-failure probe runs;
# 3. still under the probe, deploy <tag2>-flag: an image broken only where DRILL_BREAK is set, which the drill sets
#    on app2 alone. `app` updates and passes, `app2` fails, and deploy.sh must exit 1 with BOTH instances back on
#    <tag2> (no mixed versions) and .deployed-tag still <tag2>.
# Throughout, the Postgres container must never be replaced: it starts as the single-instance stack creates it (as on
# the live server, before switching), and compose.phase4.yml gives db a memory limit, which must not make a deploy
# recreate it (a Postgres restart is downtime for both instances).
# Fails unless all of that holds, the probe saw 0 failed requests, every acked send is in Postgres and its socket
# client converged.
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
# Its own compose project: compose.yml says `name: chat`, which would otherwise attach to (and replace) a local
# `chat` stack on the same machine. Run smoke.sh with the same COMPOSE_PROJECT_NAME afterwards.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-chatcheck}"

echo "== db created by the single-instance stack, as on the live server"
IMAGE_TAG="$T1" docker compose -f "$SRV/compose.yml" up -d --wait db
DB_ID="$(docker compose -f "$SRV/compose.yml" ps -q db)"

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

echo "== second instance fails under the probe: $T2 -> $T2-flag (broken on app2 only)"
docker build -q -f "$R/drill/broken-on-flag.Dockerfile" --build-arg BASE="chat-app:$T2" -t "chat-app:$T2-flag" "$R/drill" > /dev/null
docker tag "chat-web:$T2" "chat-web:$T2-flag"
python3 - "$SRV/compose.phase4.yml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
i = s.index("  app2:"); j = s.index("<<: *phase4-app-env", i) + len("<<: *phase4-app-env")
open(p, "w").write(s[:j] + '\n      DRILL_BREAK: "1"' + s[j:])
PY
rc=0; "$SRV/deploy.sh" "$T2-flag" || rc=$?
c4() { docker compose -f "$SRV/compose.yml" -f "$SRV/compose.phase4.yml" "$@"; }
img_app="$(docker inspect -f '{{.Config.Image}}' "$(c4 ps -q app)")"
img_app2="$(docker inspect -f '{{.Config.Image}}' "$(c4 ps -q app2)")"
deployed="$(cat "$SRV/.deployed-tag")"
bad=0
[ "$rc" = 1 ] || { echo "== FAILED: deploy.sh exit $rc, expected 1"; bad=1; }
[ "$img_app" = "chat-app:$T2" ] || { echo "== FAILED: app runs $img_app, expected chat-app:$T2"; bad=1; }
[ "$img_app2" = "chat-app:$T2" ] || { echo "== FAILED: app2 runs $img_app2, expected chat-app:$T2"; bad=1; }
[ "$deployed" = "$T2" ] || { echo "== FAILED: .deployed-tag is $deployed, expected $T2"; bad=1; }
for svc in app app2; do
  st="$(docker inspect -f '{{.State.Health.Status}}' "$(c4 ps -q $svc)")"
  [ "$st" = healthy ] || { echo "== FAILED: $svc is $st"; bad=1; }
done
echo "== after the failed deploy: exit $rc, app $img_app, app2 $img_app2, .deployed-tag $deployed"
[ "$(c4 ps -q db)" = "$DB_ID" ] || { echo "== FAILED: a deploy replaced the db container (Postgres restarted)"; bad=1; }

sleep 5
touch "$SRV/stop"
if ! wait "$PROBE"; then
  echo "== TWO-INSTANCE CHECK FAILED (probe)"
  exit 1
fi
if [ "$bad" != 0 ]; then
  echo "== TWO-INSTANCE CHECK FAILED (second-instance rollback or db replaced)"
  exit 1
fi
echo "== TWO-INSTANCE CHECK PASSED"
