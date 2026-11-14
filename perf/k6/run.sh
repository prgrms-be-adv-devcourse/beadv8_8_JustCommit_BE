#!/usr/bin/env bash
# k6를 내 PC에서 직접 실행하고 결과를 내 PC Prometheus(perf/monitoring)로 보낸다 (#351)
# 사용: perf/k6/run.sh <스크립트.js> [k6 run 옵션...]
#   예: perf/k6/run.sh smoke.js
# 비밀번호: ~/.replant-perf/password (레포 밖, perf/data/README.md)
# k6 설치: Windows `winget install GrafanaLabs.k6` (측정 기록은 v2.3.0)
#   Docker Desktop(Windows) 안의 k6는 서버 p95 40ms일 때 p95 0.8 ~ 2초를 재서 쓰지 않는다
#   (perf/k6/README.md 「Docker로 돌리지 않는 이유」)
set -euo pipefail
script=${1:?스크립트 이름 (예: smoke.js)}; shift
dir=$(cd "$(dirname "$0")" && pwd)
pw_file=${PERF_PASSWORD_FILE:-$HOME/.replant-perf/password}
[ -f "$pw_file" ] || { echo "비밀번호 파일 없음: $pw_file" >&2; exit 1; }
k6=${K6_BIN:-$(command -v k6 || echo "/c/Program Files/k6/k6.exe")}
[ -x "$k6" ] || { echo "k6 없음: $k6 (winget install GrafanaLabs.k6)" >&2; exit 1; }
mkdir -p "$dir/results"
summary="$dir/results/$(basename "$script" .js)-$(date +%Y%m%d-%H%M%S).json"
command -v cygpath >/dev/null && summary=$(cygpath -w "$summary") # Windows Git Bash

echo "시작: $(date --iso-8601=seconds)  (단계별 결과: python -I perf/analysis/stage_report.py --start <이 시각> ...)"
cd "$dir"
BASE_URL="${BASE_URL:-https://replant.kro.kr}" \
PERF_PASSWORD="$(cat "$pw_file")" \
K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:19090/api/v1/write}" \
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
  "$k6" run --out experimental-prometheus-rw --summary-export "$summary" "$script" "$@"
