#!/usr/bin/env bash
# 키워드 검색을 초당 요청 수 단계마다 따로 실행하고 단계별 요약을 남긴다 (#571)
# 사용: LABEL=opensearch-10k perf/k6/search-steps.sh [단계 목록, 기본 20,40,80,160,320]
#   BASE_URL(기본 http://localhost:18082) · DURATION(단계 길이, 기본 1m) · REST(단계 사이 쉬는 초, 기본 10)
# 결과: perf/k6/results/search-<LABEL>-<rps>rps-<시각>.json, 끝에 단계별 표 출력
# 판정 기준은 #351과 같음: p95 2초 초과 또는 에러율 5% 초과가 처음 나온 단계 = 한계점
set -euo pipefail
label=${LABEL:?LABEL을 정할 것 (예: opensearch-10k)}
steps=${1:-20,40,80,160,320}
dir=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$dir/results"
files=()
# 데우기: 앱을 막 띄운 직후엔 JIT · 커넥션 풀 · 캐시가 덜 데워져 낮은 단계가 오히려 느리게 나온다 (결과에 넣지 않음)
if [ "${WARMUP:-30s}" != "0" ]; then
  echo "== 데우기 ${WARMUP:-30s} (50 rps)"
  (cd "$dir" && k6 run -q -e BASE_URL="${BASE_URL:-http://localhost:18082}" -e RPS=50 \
    -e DURATION="${WARMUP:-30s}" search.js >/dev/null 2>&1) || true
  sleep "${REST:-10}"
fi
for rps in ${steps//,/ }; do
  out="$dir/results/search-$label-${rps}rps-$(date +%Y%m%d-%H%M%S).json"
  echo "== $label ${rps} rps 시작: $(date +%H:%M:%S)"
  (cd "$dir" && k6 run -q -e BASE_URL="${BASE_URL:-http://localhost:18082}" -e RPS="$rps" \
    -e DURATION="${DURATION:-1m}" --summary-export "$out" search.js >/dev/null 2>&1) || true
  files+=("$out")
  sleep "${REST:-10}"
done
python3 -I "$dir/../analysis/search_table.py" "${files[@]}"
