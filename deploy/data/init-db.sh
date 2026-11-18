#!/usr/bin/env bash
# 서비스별 DB · 계정 생성 + 운영 비밀번호 설정 (#295). 처음 한 번, 또는 서비스가 늘었을 때 다시 실행
# 서버 ~/replant 에서: deploy/data/init-db.sh
#
# 1) docker/postgres/01-service-databases.sql (로컬과 같은 파일, 여러 번 실행해도 안전)
#    → 계정 · DB · 접속 권한 · ai_db pgvector. 이 SQL은 계정 비밀번호를 계정 이름으로 만든다(로컬 개발값)
# 2) 곧바로 ~/secrets/db-accounts.env 의 값으로 비밀번호를 바꾼다
#      MEMBER_APP_PASSWORD=...  PRODUCT_APP_PASSWORD=...  MARKET_APP_PASSWORD=...  PAYMENT_APP_PASSWORD=...
#      SETTLEMENT_APP_PASSWORD=...  NOTIFICATION_APP_PASSWORD=...  AI_APP_PASSWORD=...
#    값은 앱 서버 ~/secrets/{서비스}.env 의 DB_PASSWORD와 같아야 한다. 만들 때: openssl rand -hex 24
set -euo pipefail
cd "$(dirname "$0")/../.."

secrets="${SECRETS_DIR:-$HOME/secrets}/db-accounts.env"
[ -f "$secrets" ] || { echo "없음: $secrets" >&2; exit 1; }
set -a; . "$secrets"; set +a

psql() { docker exec -i replant-postgres psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }

psql -d postgres -f /sql/01-service-databases.sql

for s in member product market payment settlement notification ai; do
  var="$(echo "$s" | tr '[:lower:]' '[:upper:]')_APP_PASSWORD"
  pw="${!var:-}"
  [ -n "$pw" ] || { echo "비어 있음: $var" >&2; exit 1; }
  # 비밀번호가 명령줄(ps)에 보이지 않도록 표준입력으로 보낸다. hex 값만 허용해 따옴표 문제를 막는다
  [[ "$pw" =~ ^[A-Za-z0-9]+$ ]] || { echo "$var 는 영문 · 숫자만 (openssl rand -hex 24)" >&2; exit 1; }
  echo "ALTER ROLE ${s}_app PASSWORD '${pw}';" | psql -d postgres
  echo "ok: ${s}_app"
done
