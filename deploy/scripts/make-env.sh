#!/usr/bin/env bash
# 앱 서버 ~/secrets/{서비스}.env 를 만들고 K3s Secret(gateway-env · market-env · ai-env)을 갱신한다.
# 값은 화면에 출력하지 않는다. 다시 실행해도 된다 (한 번 만든 키는 그대로 둔다).
#
#   bash make-env.sh
#
# 미리 있어야 하는 파일 (모두 ~/secrets, 권한 600)
#   redis.env        REDIS_PASSWORD                         (⑤-2에서 만듦)
#   db-accounts.env  MEMBER_APP_PASSWORD ~ AI_APP_PASSWORD  (데이터 서버에서 복사, #295)
#   external.env     외부 서비스 값 — 아래 EXTERNAL_KEYS 이름으로 사용자가 채움
# 처음 실행할 때 만드는 것
#   shared.env       서비스끼리 같은 값을 써야 하는 키 (내부 API 키 · 암호화 키 등)
#   jwt/private.pem · jwt/public.pem   RS256 키 한 쌍
#
# 키는 한 번 정하면 바꾸지 않는다 (docs/reference/env-vars.md 「비밀값 관리」). shared.env · jwt/ 는 백업해 둔다.
set -euo pipefail
umask 077

S="${SECRETS_DIR:-$HOME/secrets}"
DATA_IP=10.0.7.79   # 데이터 서버 사설 IP (#295)
APP_IP=10.0.7.76    # 앱 서버 사설 IP
DOMAIN=replant.kro.kr

EXTERNAL_KEYS="KAKAO_CLIENT_ID KAKAO_CLIENT_SECRET KAKAO_ADMIN_KEY KAKAO_APP_ID MAIL_USERNAME MAIL_PASSWORD TOSS_SECRET_KEY S3_BUCKET_NAME S3_PUBLIC_BASE_URL"
# 없어도 앱은 뜬다 (product 검수는 관리자 확인으로, ai 임베딩 호출만 실패). 없으면 줄 자체를 쓰지 않는다
OPTIONAL_KEYS="OPENAI_API_KEY ANTHROPIC_API_KEY"

die() { echo "❌ $*" >&2; exit 1; }
need_file() { [ -f "$S/$1" ] || die "$S/$1 이 없어요"; }
# 파일에서 KEY=값 의 값만 읽는다 (source 하지 않음)
get() { grep -m1 "^$2=" "$S/$1" 2>/dev/null | cut -d= -f2- || true; }
need() { local v; v="$(get "$1" "$2")"; [ -n "$v" ] || die "$S/$1 에 $2 값이 없어요"; printf '%s' "$v"; }

need_file redis.env
need_file db-accounts.env
need_file external.env
for k in $EXTERNAL_KEYS; do need external.env "$k" >/dev/null; done
for k in $OPTIONAL_KEYS; do [ -n "$(get external.env "$k")" ] || echo "  ⚠️ $k 없음 (선택 값이라 빼고 진행)"; done

# 1) 공유 키: 없는 것만 새로 만든다
touch "$S/shared.env"
add_shared() {  # 이름 · 만드는 명령
  if [ -z "$(get shared.env "$1")" ]; then echo "$1=$(eval "$2")" >> "$S/shared.env"; echo "  새로 만듦: $1"; fi
}
add_shared INTERNAL_API_KEY            "openssl rand -hex 32"
add_shared COURIER_API_KEY             "openssl rand -hex 32"
add_shared BANK_API_KEY                "openssl rand -hex 32"  # 모의 은행 서버 (#389)
add_shared WEBHOOK_API_KEY             "openssl rand -hex 32"
add_shared MEMBER_ADDRESS_EVENT_KEY    "openssl rand -base64 32"
add_shared ACCOUNT_ENCRYPTION_KEY      "openssl rand -base64 32"
add_shared MEMBER_HMAC_KEY             "openssl rand -base64 32"
add_shared PAYMENT_KEY_ENCRYPTION_KEY  "openssl rand -base64 32"
add_shared CASH_OUT_ACCOUNT_ENCRYPTION_KEY "openssl rand -base64 32"  # 인출 계좌번호 (결제키와 다른 키, #387)
add_shared OAUTH2_STATE_SECRET         "openssl rand -base64 32"

# 2) JWT RS256 키 한 쌍: 없을 때만 만든다. env에는 PEM 머리말을 뺀 한 줄 Base64로 넣는다
mkdir -p "$S/jwt"
if [ ! -f "$S/jwt/private.pem" ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$S/jwt/private.pem" 2>/dev/null
  openssl pkey -in "$S/jwt/private.pem" -pubout -out "$S/jwt/public.pem"
  echo "  새로 만듦: jwt/private.pem · jwt/public.pem"
fi
oneline() { grep -v -- '-----' "$1" | tr -d '\n'; }
JWT_PRIVATE_KEY="$(oneline "$S/jwt/private.pem")"
JWT_PUBLIC_KEY="$(oneline "$S/jwt/public.pem")"

sh_() { need shared.env "$1"; }
ext() { need external.env "$1"; }
opt() { local v; v="$(get external.env "$1")"; [ -z "$v" ] || echo "$1=$v"; }  # 선택 값: 있을 때만 한 줄

# 3) 서비스별 env 파일
common_db() {  # 서비스 이름
  local up; up="$(echo "$1" | tr '[:lower:]' '[:upper:]')"
  echo "DB_URL=jdbc:postgresql://$DATA_IP:5432/${1}_db"
  echo "DB_USERNAME=${1}_app"
  echo "DB_PASSWORD=$(need db-accounts.env "${up}_APP_PASSWORD")"
  echo "KAFKA_BOOTSTRAP_SERVERS=$DATA_IP:9092"
  echo "JWT_PUBLIC_KEY=$JWT_PUBLIC_KEY"
}

write() { cat > "$S/$1.env"; chmod 600 "$S/$1.env"; echo "  작성: $1.env"; }

{
  echo "JWT_PUBLIC_KEY=$JWT_PUBLIC_KEY"
  echo "REDIS_PASSWORD=$(need redis.env REDIS_PASSWORD)"
  echo "CORS_ALLOWED_ORIGINS=https://$DOMAIN"
} | write gateway

{
  common_db member
  echo "JWT_PRIVATE_KEY=$JWT_PRIVATE_KEY"
  echo "REDIS_PASSWORD=$(need redis.env REDIS_PASSWORD)"
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
  echo "MEMBER_ADDRESS_EVENT_KEY=$(sh_ MEMBER_ADDRESS_EVENT_KEY)"
  echo "ACCOUNT_ENCRYPTION_KEY=$(sh_ ACCOUNT_ENCRYPTION_KEY)"
  echo "MEMBER_HMAC_KEY=$(sh_ MEMBER_HMAC_KEY)"
  echo "OAUTH2_STATE_SECRET=$(sh_ OAUTH2_STATE_SECRET)"
  echo "KAKAO_CLIENT_ID=$(ext KAKAO_CLIENT_ID)"
  echo "KAKAO_CLIENT_SECRET=$(ext KAKAO_CLIENT_SECRET)"
  echo "KAKAO_ADMIN_KEY=$(ext KAKAO_ADMIN_KEY)"
  echo "KAKAO_APP_ID=$(ext KAKAO_APP_ID)"
  echo "MAIL_USERNAME=$(ext MAIL_USERNAME)"
  echo "MAIL_PASSWORD=$(ext MAIL_PASSWORD)"
  echo "FRONTEND_BASE_URL=https://$DOMAIN"
  echo "COOKIE_SECURE=true"
} | write member

{
  common_db product
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
  echo "AWS_REGION=ap-northeast-2"
  echo "S3_BUCKET_NAME=$(ext S3_BUCKET_NAME)"
  echo "S3_PUBLIC_BASE_URL=$(ext S3_PUBLIC_BASE_URL)"
  echo "OPENSEARCH_URL=http://$DATA_IP:9200"   # 상품 검색 (#484) · 데이터 서버 replant-opensearch (#532)
  opt OPENAI_API_KEY
  opt ANTHROPIC_API_KEY
} | write product

{
  common_db market
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
  echo "MEMBER_ADDRESS_EVENT_KEY=$(sh_ MEMBER_ADDRESS_EVENT_KEY)"
  echo "COURIER_BASE_URL=http://$APP_IP:3000"
  echo "COURIER_API_KEY=$(sh_ COURIER_API_KEY)"
  echo "WEBHOOK_API_KEY=$(sh_ WEBHOOK_API_KEY)"
} | write market

{
  common_db payment
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
  echo "TOSS_SECRET_KEY=$(ext TOSS_SECRET_KEY)"
  echo "PAYMENT_KEY_ENCRYPTION_KEY=$(sh_ PAYMENT_KEY_ENCRYPTION_KEY)"
  echo "CASH_OUT_ACCOUNT_ENCRYPTION_KEY=$(sh_ CASH_OUT_ACCOUNT_ENCRYPTION_KEY)"
  echo "BANK_BASE_URL=http://$APP_IP:3001"
  echo "BANK_API_KEY=$(sh_ BANK_API_KEY)"
} | write payment

{
  common_db settlement
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
} | write settlement

{
  common_db notification
} | write notification

{
  common_db ai
  echo "INTERNAL_API_KEY=$(sh_ INTERNAL_API_KEY)"
  opt OPENAI_API_KEY
} | write ai

# 4) K3s Secret 갱신 (있으면 덮어씀). 값이 바뀌었으면 rollout restart가 필요하다
for s in gateway market ai; do
  kubectl -n replant create secret generic "$s-env" --from-env-file="$S/$s.env" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "  K3s Secret: $s-env"
done

echo "✅ 끝. 404-express의 .env에는 shared.env의 COURIER_API_KEY · WEBHOOK_API_KEY와 같은 값을, bank의 .env에는 shared.env의 BANK_API_KEY와 같은 값을 넣어야 해요."
