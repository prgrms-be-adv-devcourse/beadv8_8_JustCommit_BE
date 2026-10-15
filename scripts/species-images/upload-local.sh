#!/bin/sh
# 받아 둔 품종 사진을 로컬 RustFS 버킷의 species/ 경로에 올린다.
# docker compose의 rustfs-init(aws-cli) 설정을 그대로 써서 계정·네트워크를 따로 지정하지 않는다.
set -e

SRC="build/species-images/species"
BUCKET="team06-replant-images"

if [ ! -d "$SRC" ]; then
  echo "$SRC 가 없습니다. download.sh를 먼저 실행하세요." >&2
  exit 1
fi

docker compose run --rm --no-deps \
  -v "$(pwd)/$SRC:/data:ro" \
  --entrypoint aws \
  rustfs-init \
  --endpoint-url http://rustfs:9000 \
  s3 sync /data "s3://$BUCKET/species"