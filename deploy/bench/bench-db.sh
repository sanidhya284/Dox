#!/bin/bash
# Benchmark database only (docs/BENCHMARKS.md). The compose project and file are hard-coded, so this
# can never stop or wipe the dev database (project "deploy", container chatplatform-postgres).
set -euo pipefail
FILE="$(cd "$(dirname "$0")" && pwd)/docker-compose.yml"
DC=(docker compose -p chatbench -f "$FILE")
case "${1:-}" in
  up)    "${DC[@]}" up -d --wait ;;
  down)  "${DC[@]}" down ;;
  reset) "${DC[@]}" down -v && "${DC[@]}" up -d --wait ;;   # -v here removes chat_bench_data only
  *)     echo "usage: $0 up|down|reset" >&2; exit 2 ;;
esac
