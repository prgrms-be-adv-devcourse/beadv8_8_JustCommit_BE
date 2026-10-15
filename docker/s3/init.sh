#!/bin/sh
# 로컬 S3(RustFS)에 운영과 같은 버킷·공개 정책·CORS를 만든다. 여러 번 실행해도 결과가 같다.
set -e

ENDPOINT="http://rustfs:9000"
BUCKET="team06-replant-images"
s3api() { aws --endpoint-url "$ENDPOINT" s3api "$@"; }

tries=0
until s3api list-buckets >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 30 ]; then
    echo "RustFS가 30초 안에 준비되지 않았습니다." >&2
    exit 1
  fi
  sleep 1
done

if ! s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  s3api create-bucket --bucket "$BUCKET"
fi
s3api put-bucket-policy --bucket "$BUCKET" --policy file:///init/bucket-policy.json
s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration file:///init/cors.json

echo "로컬 S3 준비 완료: $ENDPOINT/$BUCKET"
