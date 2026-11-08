#!/usr/bin/env bash
# Smoke checks for the compose stack (ADR-0011). Run from the repo root after
#   cd deploy && docker compose -f compose.yml -f compose.build.yml up -d --build
# Usage: deploy/smoke.sh [base-url] [app-image]
#   base-url  default https://localhost (local CA: TLS verification is skipped with -k)
#   app-image default chat-app:local
# Every check prints PASS or FAIL; the script exits non-zero if any check failed.
# The X-Forwarded-For check runs LAST: it uses up this machine's per-IP login budget for a minute.
set -uo pipefail

BASE="${1:-https://localhost}"
APP_IMAGE="${2:-chat-app:local}"
COMPOSE=(docker compose --project-directory "$(dirname "$0")" -f "$(dirname "$0")/compose.yml")
DEMO_PASSWORD="${DEMO_PASSWORD:-local-demo-password}"
failures=0

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$3', got '$2')"; fi; }
status() { curl -sk -o /dev/null -w '%{http_code}' "$@"; }

echo "== image =="
# The production image must not carry dev/bench profiles, and must refuse to start without JWT_SECRET.
# Flyway is switched off so the first startup failure can only be configuration, not a missing database.
if docker run --rm --entrypoint bash "$APP_IMAGE" -c "grep -a -q -E 'application-(dev|bench)\.yml' /app/app.jar"; then
  fail "image contains application-dev.yml or application-bench.yml"
else
  pass "image has no application-dev.yml / application-bench.yml"
fi
# Bare (no profile, no env): several fail-closed checks apply; the first one to fire stops startup.
out=$(docker run --rm -e SPRING_FLYWAY_ENABLED=false "$APP_IMAGE" 2>&1); code=$?
reason=$(grep -o -m1 -E '(ws.allowed-origins|jwt.secret) is not set' <<<"$out")
if [ $code -ne 0 ] && [ -n "$reason" ]; then
  pass "no profile, no env: refuses to start (exit $code, '$reason')"
else
  fail "no profile, no env should refuse to start (exit $code)"
fi
# Everything else present, only JWT_SECRET missing: the JWT check itself must stop startup.
out=$(docker run --rm -e SPRING_FLYWAY_ENABLED=false -e WS_ALLOWED_ORIGINS=https://example.invalid "$APP_IMAGE" 2>&1); code=$?
if [ $code -ne 0 ] && grep -q 'jwt.secret is not set' <<<"$out"; then
  pass "no profile, no JWT_SECRET: refuses to start (exit $code, 'jwt.secret is not set')"
else
  fail "no profile, no JWT_SECRET should refuse to start with 'jwt.secret is not set' (exit $code)"
fi

echo "== exposure =="
check "GET /actuator/health is 200" "$(status "$BASE/actuator/health")" 200
check "/actuator/health body is status only" "$(curl -sk "$BASE/actuator/health")" '{"status":"UP"}'
for p in /actuator /actuator/prometheus /actuator/env /actuator/metrics /actuator/info; do
  check "GET $p is 404 at the proxy" "$(status "$BASE$p")" 404
done
check "GET / (UI) is 200" "$(status "$BASE/")" 200
curl -sk "$BASE/" | grep -q '<div id="root">' && pass "UI index.html served" || fail "UI index.html served"

headers=$(curl -sk -D - -o /dev/null "$BASE/")
grep -qi '^x-content-type-options: nosniff' <<<"$headers" && pass "X-Content-Type-Options" || fail "X-Content-Type-Options"
grep -qi '^referrer-policy: no-referrer' <<<"$headers" && pass "Referrer-Policy" || fail "Referrer-Policy"
grep -qi "^content-security-policy:.*frame-ancestors 'none'" <<<"$headers" && pass "CSP frame-ancestors" || fail "CSP frame-ancestors"
if [ "$(sed -E 's#https://([^/:]+).*#\1#' <<<"$BASE")" = "localhost" ]; then
  grep -qi '^strict-transport-security' <<<"$headers" && fail "no HSTS on localhost" || pass "no HSTS on localhost (by design)"
else
  grep -qi '^strict-transport-security: max-age=31536000$' <<<"$headers" && pass "HSTS (no preload)" || fail "HSTS (no preload)"
fi
check "plain http redirects to https" "$(curl -s -o /dev/null -w '%{http_code}' "${BASE/https:/http:}/")" 308

echo "== login, ticket, socket =="
login=$(curl -sk -D - -H 'Content-Type: application/json' \
  -d "{\"username\":\"demo1\",\"password\":\"$DEMO_PASSWORD\"}" "$BASE/api/auth/login")
token=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' <<<"$login")
[ -n "$token" ] && pass "demo1 logs in through the proxy" || fail "demo1 logs in through the proxy"
grep -qi '^set-cookie: chat_rt=.*HttpOnly' <<<"$login" && grep -qi '^set-cookie: chat_rt=.*Secure' <<<"$login" \
  && pass "refresh cookie is HttpOnly + Secure" || fail "refresh cookie is HttpOnly + Secure"
ticket=$(curl -sk -X POST -H "Authorization: Bearer $token" "$BASE/api/ws/ticket" | sed -n 's/.*"ticket":"\([^"]*\)".*/\1/p')
[ -n "$ticket" ] && pass "ticket issued" || fail "ticket issued"
upgrade=$(curl -sk --http1.1 -i -N --max-time 3 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H "Origin: $BASE" \
  "$BASE/ws?ticket=$ticket" 2>/dev/null | head -1)
grep -q ' 101' <<<"$upgrade" && pass "WebSocket upgrade through Caddy (101)" || fail "WebSocket upgrade through Caddy ($upgrade)"

sleep 1
weblog=$("${COMPOSE[@]}" logs web 2>/dev/null)
grep -q '"uri":"/ws' <<<"$weblog" && pass "the /ws request is in the access log" || fail "the /ws request is in the access log"
grep -q "$ticket" <<<"$weblog" && fail "the ticket appears in the access log" || pass "the ticket is not in the access log"
grep -q 'ticket=' <<<"$weblog" && fail "a ticket= query appears in the access log" || pass "no ticket= query in the access log"

echo "== X-Forwarded-For from outside is not trusted (runs last) =="
# 21 failed logins, each claiming a different client IP. If the app trusted the header, every attempt would
# count against a different IP and none would be limited. Caddy replaces the header, so all 21 count
# against this machine and the last one is limited (20 per minute per IP).
last=""
for i in $(seq 1 21); do
  last=$(curl -sk -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 198.51.100.$i" -d "{\"username\":\"xff-nobody-$RANDOM-$i\",\"password\":\"x-password\"}" \
    "$BASE/api/auth/login")
done
check "spoofed X-Forwarded-For does not dodge the per-IP login limit (21st attempt)" "$last" 429

echo
if [ $failures -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE FAILED: $failures check(s)"; fi
exit $failures
