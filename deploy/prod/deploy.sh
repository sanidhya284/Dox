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
