#!/bin/bash
# Replant 전체 배포: 백엔드(develop) + 프론트(develop) + 택배사 404-express(main)
# 실행: EC2에서 bash deploy.sh  (레포의 scripts/deploy.sh를 서버 홈에 복사해 쓴다)
# 여러 번 실행해도 안전하다. 비밀값 원본은 ~/secrets/replant.env (지우지 않음)
#
# 주의: ~/secrets/replant.env의 DB_NAME·DB_USERNAME·DB_PASSWORD, ACCOUNT_ENCRYPTION_KEY,
#       PAYMENT_KEY_ENCRYPTION_KEY는 한 번 정하면 바꾸지 않는다.
#       DB 값은 PostgreSQL 볼륨을 처음 만들 때만 적용되고, 암호화 키를 바꾸면 저장된 값을 복호화할 수 없다.
set -e
trap 'echo; echo "❌ 실패한 줄: $LINENO  (명령: $BASH_COMMAND)"' ERR

BE_REPO=https://github.com/prgrms-be-adv-devcourse/beadv8_8_JustCommit_BE.git
FE_REPO=https://github.com/prgrms-be-adv-devcourse/beadv8_8_JustCommit_FE.git
COURIER_REPO=https://github.com/gasic37200/404-express.git
DOMAIN=replant.kro.kr

echo "===== 0. 준비물 확인 ====="
mkdir -p ~/secrets && chmod 700 ~/secrets
if [ ! -f ~/secrets/replant.env ]; then
  for f in ~/replant/.env ~/replant-old/.env; do
    [ -f "$f" ] && cp "$f" ~/secrets/replant.env && break
  done
fi
[ -f ~/secrets/replant.env ] || { echo "❌ 비밀값 .env를 찾지 못했어요 (~/secrets/replant.env)"; exit 1; }
chmod 600 ~/secrets/replant.env
if grep -q -E '^KAKAO_CLIENT_(ID|SECRET)=$' ~/secrets/replant.env; then
  echo "❌ KAKAO 값이 비어 있어요. nano ~/secrets/replant.env 로 채운 뒤 다시 실행하세요"; exit 1
fi
echo "✅ 비밀값, 카카오 값 확인"

echo "===== 1. 기존 컨테이너 내리기 (데이터 볼륨은 유지) ====="
[ -f ~/replant/docker-compose.prod.yml ] && (cd ~/replant && docker compose -f docker-compose.prod.yml down) || true
[ -f ~/404-express/docker-compose.prod.yml ] && (cd ~/404-express && docker compose -f docker-compose.prod.yml down) || true
docker rm -f replant-redis replant-app replant-postgres replant-kafka >/dev/null 2>&1 || true

echo "===== 2. 기존 폴더 지우기 ====="
rm -rf ~/replant ~/replant-old ~/404-express ~/replant-fe ~/fe-dist

echo "===== 3. 공용 네트워크 ====="
docker network inspect replant-net >/dev/null 2>&1 || docker network create replant-net

echo "===== 4. 백엔드 develop + .env ====="
git clone -q -b develop "$BE_REPO" ~/replant
cd ~/replant
cp ~/secrets/replant.env .env && chmod 600 .env
fill() {
  if grep -q "^$1=" .env; then
    if [ -z "$(grep "^$1=" .env | cut -d= -f2-)" ]; then sed -i "s|^$1=.*|$1=$2|" .env; fi
  else printf '%s=%s\n' "$1" "$2" >> .env; fi
}
fill COURIER_BASE_URL http://404-express:3000
fill COURIER_API_KEY "$(openssl rand -hex 32)"
fill WEBHOOK_API_KEY "$(openssl rand -hex 32)"
if ! grep -q '^PAYMENT_KEY_ENCRYPTION_KEY=.\+' .env; then
  fill PAYMENT_KEY_ENCRYPTION_KEY "$(openssl rand -base64 32)"
  echo "🔑 PAYMENT_KEY_ENCRYPTION_KEY를 새로 만들었어요. 비밀번호 관리자에 백업하세요"
fi
grep -q '^FRONTEND_BASE_URL=' .env || echo 'FRONTEND_BASE_URL=' >> .env
sed -i "s|^FRONTEND_BASE_URL=.*|FRONTEND_BASE_URL=https://$DOMAIN|" .env
cp .env ~/secrets/replant.env

# 새 코드가 요구하는 변수가 서버 .env에 빠졌는지 검사 (.env.example 기준)
missing=$(comm -23 <(grep -oE '^[A-Z_]+=' .env.example | sort -u) <(grep -oE '^[A-Z_]+=' .env | sort -u) | tr -d '=')
if [ -n "$missing" ]; then
  echo "❌ 서버 .env에 없는 변수: $(echo $missing)"
  echo "   nano ~/secrets/replant.env 로 추가한 뒤 다시 실행하세요 (.env.example 주석에 만드는 법이 있어요)"; exit 1
fi
# 비어 있으면 Spring이 기동에 실패하는 값
for k in DB_PASSWORD REDIS_PASSWORD JWT_SECRET ACCOUNT_ENCRYPTION_KEY PAYMENT_KEY_ENCRYPTION_KEY KAKAO_CLIENT_ID KAKAO_CLIENT_SECRET; do
  grep -q "^$k=.\+" .env || { echo "❌ $k 값이 비어 있어요. nano ~/secrets/replant.env 로 채우세요"; exit 1; }
done
echo "✅ 백엔드: $(git log --oneline -1)"

echo "===== 5. 택배사 main + .env (키는 백엔드와 같은 값) ====="
git clone -q "$COURIER_REPO" ~/404-express
cd ~/404-express && cp .env.example .env
for k in COURIER_API_KEY WEBHOOK_API_KEY; do
  v=$(grep "^$k=" ~/replant/.env | cut -d= -f2-)
  sed -i "s|^$k=.*|$k=$v|" .env
done
sed -i 's|^WEBHOOK_URL=.*|WEBHOOK_URL=http://replant-app:8080/api/v1/webhooks/courier|' .env
sed -i "s|^ADMIN_API_KEY=.*|ADMIN_API_KEY=$(openssl rand -hex 32)|" .env
chmod 600 .env
echo "✅ 택배사: $(git log --oneline -1)"

echo "===== 6. 프론트 develop 받아서 빌드 (Docker 안의 Node, 서버에 Node 설치 안 함) ====="
git clone -q -b develop "$FE_REPO" ~/replant-fe
cd ~/replant-fe
# 토스 클라이언트 키는 브라우저에 노출되는 공개 키라 빌드 결과에 들어간다 (시크릿 키는 백엔드 .env에만)
TOSS_CK=$(grep '^TOSS_CLIENT_KEY=' ~/secrets/replant.env | cut -d= -f2-)
[ -n "$TOSS_CK" ] || echo "⚠️ TOSS_CLIENT_KEY가 없어 예치금 충전 결제창이 열리지 않아요 (~/secrets/replant.env에 test_ck_... 추가)"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -e VITE_API_BASE_URL= -e VITE_OAUTH_BASE_URL="https://$DOMAIN" -e VITE_TOSS_CLIENT_KEY="$TOSS_CK" \
  -v "$PWD":/app -w /app node:24-alpine \
  sh -c "npm ci --no-audit --no-fund --loglevel=error && npm run build"
[ -f dist/index.html ] || { echo "❌ 프론트 빌드 결과(dist/index.html)가 없어요"; exit 1; }
if grep -q "localhost:8080" dist/assets/*.js; then
  echo "❌ 프론트 빌드에 localhost:8080이 남아 있어요 (카카오 로그인 주소 설정 확인)"; exit 1
fi
if [ -n "$TOSS_CK" ] && ! grep -q "$TOSS_CK" dist/assets/*.js; then
  echo "❌ 프론트 빌드에 토스 클라이언트 키가 들어가지 않았어요"; exit 1
fi
sudo mkdir -p /var/www/replant
sudo rm -rf /var/www/replant/*
sudo cp -r dist/* /var/www/replant/
echo "✅ 프론트: $(git log --oneline -1)"

echo "===== 7. Nginx: / 는 프론트, API·로그인 경로는 백엔드 ====="
sudo tee /etc/nginx/sites-available/replant > /dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name replant.kro.kr;
    return 301 https://$host$request_uri;
}
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name replant.kro.kr;
    ssl_certificate     /etc/letsencrypt/live/replant.kro.kr/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/replant.kro.kr/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
    client_max_body_size 10m;

    root /var/www/replant;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
    location /assets/ { expires 30d; add_header Cache-Control "public, immutable"; }

    location ~ ^/(api|oauth2|login/oauth2|actuator|swagger-ui|v3/api-docs)(/|$) {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Port  $server_port;
    }
}
NGINX
sudo nginx -t && sudo systemctl reload nginx
echo "✅ 프론트는 지금부터 https://$DOMAIN 에서 보여요"

echo "===== 8. 띄우기: 택배사 → 백엔드 (백엔드 첫 빌드 10분 이상) ====="
cd ~/404-express && docker compose -f docker-compose.prod.yml up -d --build
cd ~/replant && docker compose -f docker-compose.prod.yml up -d --build

echo "===== 9. 확인 ====="
echo -n "택배사 (replant-net 안에서): "
docker run --rm --network replant-net curlimages/curl -s http://404-express:3000/health; echo
echo -n "백엔드 health 대기"
s=000
for i in $(seq 1 36); do
  s=$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/actuator/health" || true)
  [ "$s" = 200 ] && break; echo -n "."; sleep 5
done
echo " → HTTP $s"
if [ "$s" = 200 ]; then
  # 품종 데이터는 운영에서 자동 적재되지 않는다. 이미 있는 행은 건너뛰므로 매번 실행해도 된다
  cd ~/replant
  docker compose -f docker-compose.prod.yml exec -T postgres sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < src/main/resources/db/data/species.sql
  echo -n "품종 수: "
  docker compose -f docker-compose.prod.yml exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) from species"'
else
  echo "❌ 백엔드가 뜨지 않았어요. 원인:"
  docker logs replant-app --tail 80 2>&1 | grep -E "Caused by|Could not resolve|FATAL" | tail -3
fi
echo -n "프론트: "; curl -s -o /dev/null -w "HTTP %{http_code}\n" "https://$DOMAIN/"
docker ps --format 'table {{.Names}}\t{{.Status}}'
echo "🎉 끝"
