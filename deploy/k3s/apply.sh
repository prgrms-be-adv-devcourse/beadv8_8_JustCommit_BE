#!/usr/bin/env bash
# K3s 매니페스트를 적용하되, 이미 떠 있는 Deployment의 이미지(CD가 배포한 커밋 태그)는 그대로 둔다 (#590).
# 매니페스트의 image는 처음 올리기용 :latest라서, 그냥 kubectl apply -f market.yaml 을 하면 옛 :latest로 되돌아간다.
#
#   bash deploy/k3s/apply.sh deploy/k3s/market.yaml            # 하나
#   bash deploy/k3s/apply.sh deploy/k3s/*.yaml                  # 여러 개 (ConfigMap · Namespace도 그대로 적용)
#
# Deployment가 아직 없으면(처음 올리기) 매니페스트 그대로 :latest로 만든다. 그 뒤로는 CD의 set image가 태그를 바꾼다.
set -euo pipefail

NS=replant
[ $# -gt 0 ] || { echo "사용: bash deploy/k3s/apply.sh <매니페스트.yaml>..." >&2; exit 1; }

for f in "$@"; do
  # 이 파일에 들어 있는 Deployment 이름들 (gateway · market · ai)
  names=$(awk '/^kind: Deployment/{d=1} d&&/^  name:/{print $2; d=0}' "$f")
  manifest=$(cat "$f")
  for name in $names; do
    current=$(kubectl -n "$NS" get deploy "$name" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
    if [ -n "$current" ]; then
      # 파일의 image 줄을 지금 쓰는 이미지로 바꿔서 적용 → 이미지 때문에 파드가 바뀌지 않음
      manifest=$(printf '%s\n' "$manifest" | sed -E "s#^( +image: ).*/replant-$name:.*#\1$current#")
      echo "$name: 지금 이미지 유지 ($current)"
    else
      echo "$name: 아직 없음 → 매니페스트 그대로 만듦"
    fi
  done
  printf '%s\n' "$manifest" | kubectl apply -f -
done
