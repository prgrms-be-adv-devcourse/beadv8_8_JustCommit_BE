#!/usr/bin/env bash
# 가짜 외부 서버의 응답 지연을 바꾼다 (가설 4: 외부 HTTP 지연 → 주문 · 충전 p95). 재시작 없이 바로 적용된다.
# 사용: perf/fakes/set-delay.sh <courier|toss> <밀리초>
#   예: perf/fakes/set-delay.sh courier 1000
# WIREMOCK_URL: 기본 http://localhost:18089 (SSH 터널 입구, perf/monitoring/README.md)
set -euo pipefail
target=${1:?courier 또는 toss}
ms=${2:?지연 밀리초}
url=${WIREMOCK_URL:-http://localhost:18089}
dir=$(cd "$(dirname "$0")" && pwd)

case "$target" in
  courier) file="$dir/mappings/courier.json" ;;
  toss) file="$dir/mappings/toss-confirm.json" ;;
  *) echo "courier 또는 toss" >&2; exit 1 ;;
esac

id=$(grep -o '"id": *"[^"]*"' "$file" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
sed "s/\"fixedDelayMilliseconds\": *[0-9]*/\"fixedDelayMilliseconds\": $ms/" "$file" \
  | curl -sf -X PUT "$url/__admin/mappings/$id" -H 'Content-Type: application/json' --data-binary @- >/dev/null
echo "$target 지연 = ${ms}ms"
