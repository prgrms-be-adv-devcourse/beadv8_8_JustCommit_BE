#!/usr/bin/env bash
# k6를 Docker로 실행하고 결과를 내 PC Prometheus(perf/monitoring)로 보낸다 (#351)
# 사용: perf/k6/run.sh <스크립트.js> [k6 run 옵션...]
#   예: perf/k6/run.sh smoke.js
# 비밀번호: ~/.replant-perf/password (레포 밖, perf/data/README.md)
set -euo pipefail
script=${1:?스크립트 이름 (예: smoke.js)}; shift
dir=$(cd "$(dirname "$0")" && pwd)
pw_file=${PERF_PASSWORD_FILE:-$HOME/.replant-perf/password}
[ -f "$pw_file" ] || { echo "비밀번호 파일 없음: $pw_file" >&2; exit 1; }
mount=$dir; command -v cygpath >/dev/null && mount=$(cygpath -w "$dir") # Windows Git Bash
mkdir -p "$dir/results"

MSYS_NO_PATHCONV=1 docker run --rm -i \
  -v "$mount:/scripts" \
  -e BASE_URL="${BASE_URL:-https://replant.kro.kr}" \
  -e PERF_PASSWORD="$(cat "$pw_file")" \
  -e K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://host.docker.internal:19090/api/v1/write}" \
  -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
  --add-host host.docker.internal:host-gateway \
  grafana/k6:1.0.0 run --out experimental-prometheus-rw \
  --summary-export "/scripts/results/$(basename "$script" .js)-$(date +%Y%m%d-%H%M%S).json" \
  "/scripts/$script" "$@"
