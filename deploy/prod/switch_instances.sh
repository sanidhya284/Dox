#!/usr/bin/env bash
# Switch the server between one and two app instances (ADR-0013; runbook "Two instances").
#   /opt/chat/switch_instances.sh two   # needs compose.phase4.yml and caddy/Caddyfile next to compose.yml
#   /opt/chat/switch_instances.sh one   # back to the single instance (moves compose.phase4.yml aside)
# Runs the currently deployed tag (.deployed-tag); it never deploys a new version. The order keeps an instance
# serving throughout; the one unavoidable gap is Caddy's restart when `web` changes config (a few seconds).
set -euo pipefail

MODE="${1:?usage: switch_instances.sh two|one}"
DIR="${CHAT_DIR:-/opt/chat}"
cd "$DIR"
TAG="$(cat .deployed-tag)"
export IMAGE_TAG="$TAG"
one() { docker compose -f "$DIR/compose.yml" "$@"; }
two() { docker compose -f "$DIR/compose.yml" -f "$DIR/compose.phase4.yml" "$@"; }
check() { python3 "$DIR/postdeploy_check.py" --env "$DIR/.env" --tag "$1"; }

wait_healthy() {  # <compose function> <service>
  local id deadline=$((SECONDS + 180))
  while :; do
    id="$("$1" ps -q "$2")"
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || true)" = "healthy" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { echo "== $2 not healthy after 180s"; return 1; }
    sleep 2
  done
}

case "$MODE" in
  two)
    [ -f compose.phase4.yml ] && [ -f caddy/Caddyfile ] || { echo "compose.phase4.yml (cp compose.phase4.yml.example compose.phase4.yml) and caddy/Caddyfile needed"; exit 2; }
    echo "== starting redis and app2 on $TAG"
    two up -d --no-deps redis app2
    wait_healthy two app2
    echo "== web: Caddy now balances app and app2 (Caddy restarts: a short blip)"
    two up -d --no-deps web
    echo "== app: restarted with the two-instance settings (app2 serves meanwhile)"
    two up -d --no-deps app
    wait_healthy two app
    sha256sum caddy/Caddyfile | cut -d' ' -f1 > .caddyfile.sha
    check "$TAG"
    echo "== TWO INSTANCES on $TAG"
    ;;
  one)
    echo "== app: restarted with the single-instance settings (app2 serves meanwhile)"
    one up -d --no-deps app
    wait_healthy one app
    echo "== web: Caddy back to app only (Caddy restarts: a short blip)"
    one up -d --no-deps web
    echo "== removing app2 and redis"
    two rm -s -f app2 redis
    mv compose.phase4.yml compose.phase4.yml.off
    check "$TAG"
    echo "== ONE INSTANCE on $TAG (compose.phase4.yml moved to compose.phase4.yml.off)"
    ;;
  *) echo "usage: switch_instances.sh two|one"; exit 2 ;;
esac
