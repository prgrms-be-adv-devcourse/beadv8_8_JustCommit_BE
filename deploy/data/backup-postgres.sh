#!/usr/bin/env bash
# 서비스 DB 하루 1회 백업 → S3 (#295). 데이터 서버 cron이 매일 17:30(KST)에 실행
#   EC2는 09:00~18:00만 켜져 있어 새벽 cron은 돌지 않는다 → 꺼지기 전에 그날 데이터를 담는다
#
# 결과: s3://team06-replant-images/backups/postgres/{YYYY-MM-DD}/{db}.dump  (pg_dump -Fc, DB마다 한 파일)
#   보관 기간은 S3 수명 주기 규칙(backups/ 경로, 7일)이 지운다
#   AWS 키 없이 EC2 IAM 역할로 올린다 (backups/* PutObject)
#
# 복원 (예: market_db):
#   deploy/data/init-db.sh                                  # 계정 · 빈 DB가 없으면 먼저
#   aws s3 cp s3://.../market_db.dump - | docker exec -i replant-postgres pg_restore -U postgres -d market_db --clean --if-exists --no-owner --role=market_app
set -euo pipefail

bucket="${BACKUP_BUCKET:-team06-replant-images}"
day="$(TZ=Asia/Seoul date +%F)"
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT

dbs=$(docker exec replant-postgres psql -U postgres -tAc "select datname from pg_database where datname like '%\_db' order by 1")
[ -n "$dbs" ] || { echo "$(date -Is) 백업할 서비스 DB가 없음" >&2; exit 1; }

for db in $dbs; do
  docker exec replant-postgres pg_dump -U postgres -Fc "$db" > "$dir/$db.dump"
done

# --network host: 컨테이너가 EC2 메타데이터(IAM 역할 임시 자격 증명)를 홉 제한과 상관없이 읽게 한다
docker run --rm --network host -v "$dir:/backup:ro" -e AWS_DEFAULT_REGION=ap-northeast-2 \
  amazon/aws-cli:2.38.0 s3 cp /backup "s3://$bucket/backups/postgres/$day/" --recursive --only-show-errors

echo "$(date -Is) ok: $(echo $dbs | wc -w)개 DB → s3://$bucket/backups/postgres/$day/ ($(du -sh "$dir" | cut -f1))"
