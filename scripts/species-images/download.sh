#!/bin/sh
# species.sql의 농사로 사진을 내려받아 S3 키 구조(species/{농사로ID}/{파일명})로 저장한다.
# 이미 받은 파일은 건너뛰므로 여러 번 실행해도 된다.
set -e

SQL="src/main/resources/db/data/species.sql"
OUT="build/species-images/species"

total=0
failed=0
for url in $(grep -o "https://nongsaro.go.kr/cms_contents/301/[^']*" "$SQL"); do
  file="${url##*/}"          # 12938_MF_REPR_ATTACH_01.jpg
  id="${file%%_*}"           # 12938
  name="${file#*_}"          # MF_REPR_ATTACH_01.jpg
  dest="$OUT/$id/$name"
  total=$((total + 1))

  if [ -s "$dest" ]; then
    continue
  fi
  mkdir -p "$OUT/$id"
  if curl -fsS --retry 3 -o "$dest" "$url"; then
    sleep 0.2                # 농사로 서버에 부담 주지 않도록 천천히
  else
    echo "실패: $url" >&2
    rm -f "$dest"
    failed=$((failed + 1))
  fi
done

echo "전체 $total장, 실패 $failed장 → $OUT"