#!/usr/bin/env bash
# Deploy one image tag on the server (ADR-0011). Run by the GitHub deploy job as the `deploy` user:
#   /opt/chat/deploy.sh sha-abc1234
# 1. pull the tag, 2. `up -d` (Flyway migrates at app startup), 3. post-deploy check through Caddy
# (health, demo login, ticket, socket, send + ack, read back). On failure: redeploy the previous good tag,
# check it again, and exit 1 so the job fails. Rolling back across a migration works only for additive
# (expand/contract) migrations; Flyway ignores newer applied migrations it doesn't know.
set -euo pipefail

TAG="${1:?usage: deploy.sh <image-tag>}"
DIR="${CHAT_DIR:-/opt/chat}"
cd "$DIR"
compose() { docker compose -f "$DIR/compose.yml" "$@"; }
check() { python3 "$DIR/postdeploy_check.py" --env "$DIR/.env" --tag "$1"; }

PREV="$(cat .deployed-tag 2>/dev/null || true)"
echo "== deploying $TAG (previous good: ${PREV:-none})"

# ── Two instances (ADR-0013; runbook "switching to two instances") ──────────────────────────────────────────
# Only when compose.phase4.yml sits next to compose.yml. One instance at a time: Docker stops the old container
# (SIGTERM: drain 6 s while Caddy takes it out of rotation, then graceful shutdown; stop_grace_period 35 s) and
# the next one is touched only after this one is healthy, so the other instance serves throughout. A failed
# instance is rolled back on its own. Without the file, the single-instance path below runs, unchanged.
if [ -f "$DIR/compose.phase4.yml" ]; then
  compose4() { docker compose -f "$DIR/compose.yml" -f "$DIR/compose.phase4.yml" "$@"; }

  wait_healthy() {  # <service>: healthy within HEALTH_WAIT s, or fail early when it keeps restarting
    local id st rc deadline=$((SECONDS + ${HEALTH_WAIT:-180}))
    while :; do
      id="$(compose4 ps -q "$1")"
      st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo missing)"
      rc="$(docker inspect -f '{{.RestartCount}}' "$id" 2>/dev/null || echo 0)"
      [ "$st" = "healthy" ] && return 0
      if [ "$rc" -ge 2 ]; then echo "== $1 keeps restarting (restarts: $rc)"; return 1; fi
      if [ "$SECONDS" -ge "$deadline" ]; then echo "== $1 not healthy after ${HEALTH_WAIT:-180}s ($st)"; return 1; fi
      sleep 2
    done
  }

  roll() {  # <service> <tag>
    echo "== $1 -> $2"
    IMAGE_TAG="$2" compose4 up -d --no-deps "$1" || true
    wait_healthy "$1"
  }

  web_image() { IMAGE_TAG="$1" compose4 config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["web"]["image"])'; }

  update_web() {  # <tag>: the UI from the image, the Caddyfile from ./caddy (mounted)
    local wid ui_new ui_old sha
    sha="$(sha256sum "$DIR/caddy/Caddyfile" | cut -d' ' -f1)"
    wid="$(compose4 ps -q web)"
    if [ -z "$wid" ]; then
      IMAGE_TAG="$1" compose4 up -d --no-deps web
    else
      ui_new="$(docker run --rm --entrypoint sh "$(web_image "$1")" -c 'cd /srv && find . -type f -exec sha256sum {} + | sort | sha256sum')"
      ui_old="$(docker exec "$wid" sh -c 'cd /srv && find . -type f -exec sha256sum {} + | sort | sha256sum')"
      if [ "$ui_new" != "$ui_old" ]; then
        echo "== the UI changed: recreating web (Caddy restarts: a blip of a few seconds; runbook)"
        IMAGE_TAG="$1" compose4 up -d --no-deps web
      elif [ "$sha" != "$(cat .caddyfile.sha 2>/dev/null || true)" ]; then
        echo "== only the Caddyfile changed: reloading it in place (no restart)"
        docker exec "$wid" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
      else
        echo "== web unchanged"
      fi
    fi
    echo "$sha" > .caddyfile.sha
  }

  [ "${SKIP_PULL:-0}" = "1" ] || IMAGE_TAG="$TAG" compose4 pull --quiet
  compose4 up -d --no-deps db redis   # no-ops when running; a first deploy needs the database up first
  wait_healthy db
  for svc in app app2; do
    if ! roll "$svc" "$TAG"; then
      compose4 logs --tail 60 "$svc" || true
      if [ -z "$PREV" ]; then echo "== no previous good tag (first deploy)"; exit 1; fi
      echo "== ROLLING BACK $svc to $PREV; the other instance keeps serving"
      roll "$svc" "$PREV" || echo "== $svc is not healthy on $PREV either: investigate by hand"
      [ "$svc" = "app2" ] && { roll app "$PREV" || echo "== app is not healthy on $PREV either"; }
      exit 1
    fi
  done
  update_web "$TAG"
  if check "$TAG"; then
    [ -n "$PREV" ] && [ "$PREV" != "$TAG" ] && echo "$PREV" > .deployed-tag.prev
    echo "$TAG" > .deployed-tag
    docker image prune -f > /dev/null
    echo "== DEPLOYED $TAG (two instances)"
    exit 0
  fi
  echo "== CHECK FAILED for $TAG"
  if [ -z "$PREV" ]; then exit 1; fi
  echo "== ROLLING BACK both instances to $PREV, one at a time"
  roll app "$PREV" || echo "== app is not healthy on $PREV"
  roll app2 "$PREV" || echo "== app2 is not healthy on $PREV"
  update_web "$PREV"
  if check "$PREV"; then echo "== ROLLED BACK: $PREV is serving again"; else echo "== ROLLBACK CHECK ALSO FAILED"; fi
  exit 1
fi

export IMAGE_TAG="$TAG"
# A failed pull changes nothing (the old containers keep running): fail without touching the stack.
[ "${SKIP_PULL:-0}" = "1" ] || compose pull --quiet
# `up -d` itself fails when the new app never becomes healthy (web waits for it). That must NOT end the
# script before the rollback below, so its result is only logged; the post-deploy check decides.
compose up -d --remove-orphans || echo "== compose up reported a failure for $TAG; the check decides"

if check "$TAG"; then
  [ -n "$PREV" ] && [ "$PREV" != "$TAG" ] && echo "$PREV" > .deployed-tag.prev
  echo "$TAG" > .deployed-tag
  docker image prune -f > /dev/null   # dangling layers only; tagged images (rollback targets) stay
  echo "== DEPLOYED $TAG"
  exit 0
fi

echo "== CHECK FAILED for $TAG"
compose logs --tail 80 app || true
if [ -z "$PREV" ]; then
  echo "== no previous good tag to roll back to (first deploy); leaving the stack as it is"
  exit 1
fi
echo "== ROLLING BACK to $PREV"
export IMAGE_TAG="$PREV"
compose up -d --remove-orphans || echo "== compose up reported a failure for $PREV; the check decides"
if check "$PREV"; then
  echo "== ROLLED BACK: $PREV is serving again"
else
  echo "== ROLLBACK CHECK ALSO FAILED: investigate by hand (docker compose -f $DIR/compose.yml logs)"
fi
exit 1
