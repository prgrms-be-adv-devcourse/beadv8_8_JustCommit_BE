#!/usr/bin/env bash
# MSA 운영 측정용 테스트 데이터 (#628) — 세미 seed.sql · more-products.sql · bulk-orders.sql과 같은 양을 서비스별 DB에 나눠 넣는다
#
# 사용 (내 PC Git Bash, ssh replant-data가 비밀번호 없이 되어야 함):
#   perf/data/msa/seed.sh
#   개수 바꾸기: BUYERS=2000 SELLERS=200 PRODUCTS=10000 ORDER_PRODUCTS=30000 BULK_ORDERS=110000 perf/data/msa/seed.sh
#
# 순서: member_db 회원 → (회원 번호 그대로) market · product · payment 회원 복제본 → payment 지갑 → product 상품 → market 지난 주문
# 회원 복제본은 원래 Kafka 이벤트로 채워지지만, 2천 명 가입 이벤트를 흘리는 대신 SQL로 같은 행을 넣는다.
# 비밀번호는 ~/.replant-perf/password (레포 밖)를 bcrypt로 바꿔 해시만 넘긴다.
# 되돌리기: seed 전에 시연 도구 `npm run demo -- save <이름>`, 측정 뒤 `restore <이름>` (restore base는 그 뒤 생긴 실제 데이터까지 지움, #661)
set -euo pipefail

DATA=${DATA_HOST:-replant-data}
BUYERS=${BUYERS:-2000}
SELLERS=${SELLERS:-200}
PRODUCTS=${PRODUCTS:-10000}             # 둘러보기용 판매 중 상품 (세미 seed.sql)
ORDER_PRODUCTS=${ORDER_PRODUCTS:-30000} # 주문 측정용 판매 중 상품 (세미 more-products.sql) — 주문마다 1개씩 품절
BULK_ORDERS=${BULK_ORDERS:-110000}      # 내 주문 목록용 지난 주문 (세미 1.1만 + bulk 10만)
pw_file=${PERF_PASSWORD_FILE:-$HOME/.replant-perf/password}

[ -f "$pw_file" ] || { echo "비밀번호 파일 없음: $pw_file" >&2; exit 1; }
# bcrypt 해시: PERF_HASH를 주면 그대로(Docker가 없을 때), 아니면 세미처럼 httpd 이미지의 htpasswd로 만든다
HASH=${PERF_HASH:-$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 x "$(cat "$pw_file")" | cut -d: -f2 | tr -d '\r\n')}
[[ "$HASH" == \$2* ]] || { echo "bcrypt 해시 만들기 실패" >&2; exit 1; }

# 데이터 서버 postgres 컨테이너 안 psql에 표준 입력으로 SQL을 넘긴다
psql_db() { ssh -o BatchMode=yes "$DATA" "docker exec -i replant-postgres sh -c 'psql -U \$POSTGRES_USER -d $1 -v ON_ERROR_STOP=1 -q -At'"; }

step() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------- 1. member_db: 구매자 · 판매자 · 관리자 1명(색인 API용) ----------
step "1. member_db 회원"
sql=$(cat <<'SQL'
BEGIN;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM member WHERE email LIKE 'perf-%@perf.test') THEN
    RAISE EXCEPTION '이미 테스트 회원이 있음: 측정 전 스냅샷으로 restore 뒤 다시 실행';
  END IF;
END $$;
INSERT INTO member (email, password, nickname, phone, role, provider, status, created_at, updated_at, token_version)
SELECT 'perf-buyer-' || g || '@perf.test', '__HASH__', 'perfbuyer' || g, '0109' || lpad(g::text, 7, '0'),
       'MEMBER', 'LOCAL', 'ACTIVE', now(), now(), 0
FROM generate_series(1, __BUYERS__) g;
INSERT INTO member (email, password, nickname, phone, role, provider, status, created_at, updated_at, token_version)
SELECT 'perf-seller-' || g || '@perf.test', '__HASH__', 'perfseller' || g, '0108' || lpad(g::text, 7, '0'),
       'SELLER', 'LOCAL', 'ACTIVE', now(), now(), 0
FROM generate_series(1, __SELLERS__) g;
INSERT INTO member (email, password, nickname, phone, role, provider, status, created_at, updated_at, token_version)
VALUES ('perf-admin@perf.test', '__HASH__', 'perfadmin', '01070000001', 'ADMIN', 'LOCAL', 'ACTIVE', now(), now(), 0);
INSERT INTO seller (member_id, intro, created_at, updated_at, review_consent_version, review_consented_at)
SELECT id, '부하 테스트 판매자', now(), now(), 'v1', now() FROM member WHERE email LIKE 'perf-seller-%@perf.test';
INSERT INTO address (member_id, recipient_name, recipient_phone, zipcode, address1, address2, address_name, is_default, created_at, updated_at)
SELECT id, '부하판매자', phone, '06236', '서울시 강남구 테헤란로 ' || id, NULL, '수거지', true, now(), now()
FROM member WHERE email LIKE 'perf-seller-%@perf.test';
COMMIT;
SQL
)
sql=${sql//__HASH__/$HASH}; sql=${sql//__BUYERS__/$BUYERS}; sql=${sql//__SELLERS__/$SELLERS}
psql_db member_db <<<"$sql"

# 회원 번호 · 닉네임 · 역할을 CSV로 받아 다른 DB 복제본에 그대로 넣는다
members_csv=$(psql_db member_db <<<"SELECT id || ',' || nickname || ',' || role FROM member WHERE email LIKE 'perf-%@perf.test' ORDER BY id;")
step "   회원 $(wc -l <<<"$members_csv")명"

replica_sql() { # $1 = INSERT 문 (임시 표 m에서 고름)
  printf '%s\n' "BEGIN;" "CREATE TEMP TABLE m (member_id bigint, nickname text, role text) ON COMMIT DROP;" \
    "COPY m FROM STDIN WITH (FORMAT csv);"
  printf '%s\n' "$members_csv" '\.'
  printf '%s\n' "$1" "COMMIT;"
}

# ---------- 2. 회원 복제본 (market · product · payment) ----------
step "2. 회원 복제본"
psql_db market_db < <(replica_sql "INSERT INTO member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, updated_at)
  SELECT member_id, nickname, role, role = 'SELLER', false, now(), now() FROM m;")
psql_db product_db < <(replica_sql "INSERT INTO member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, created_at, updated_at)
  SELECT member_id, nickname, role, role = 'SELLER', false, now(), now(), now() FROM m;")
# ---------- 3. 지갑 (구매자 1억, 판매자 · 관리자 0원) — 토스 충전을 거치지 않음 ----------
step "3. payment 복제본 · 지갑"
psql_db payment_db < <(replica_sql "INSERT INTO member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, updated_at)
  SELECT member_id, nickname, role, role = 'SELLER', false, now(), now() FROM m;
INSERT INTO wallet (wallet_type, member_id, balance, version, created_at, updated_at, receivable)
  SELECT 'MEMBER', member_id, CASE WHEN role = 'MEMBER' THEN 100000000 ELSE 0 END, 0, now(), now(), 0 FROM m;")

# ---------- 4. product: 둘러보기용 + 주문용 판매 중 상품 ----------
step "4. product 상품 $PRODUCTS + 주문용 $ORDER_PRODUCTS"
sql=$(cat <<'SQL'
BEGIN;
CREATE TEMP TABLE s ON COMMIT DROP AS
  SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM member_replica WHERE nickname LIKE 'perfseller%';
CREATE TEMP TABLE sp ON COMMIT DROP AS SELECT row_number() OVER (ORDER BY id) AS n, id, name FROM species;
CREATE TEMP TABLE base ON COMMIT DROP AS SELECT COALESCE(max(id), 0) AS v FROM product;
-- 가격 1만 ~ 10만, 품종 · 판매자를 고르게 (세미 seed.sql과 같은 식). 앞 __PRODUCTS__개 = 둘러보기용, 뒤 = 주문용
INSERT INTO product (species_id, seller_id, title, description, price, height_cm, pot_size, grow_note, status, is_hidden,
                     created_at, updated_at, review_version, resubmit_count, version)
SELECT sp.id, s.id, sp.name || ' ' || g, sp.name || ' 부하 테스트 상품입니다.', (1 + g % 10) * 10000, 10 + g % 50, 'M', NULL,
       'ACTIVE', false, now() - (g || ' seconds')::interval, now(), 0, 0, 0
FROM generate_series(1, __PRODUCTS__ + __ORDER_PRODUCTS__) g
JOIN s ON s.n = 1 + g % (SELECT count(*) FROM s)
JOIN sp ON sp.n = 1 + g % (SELECT count(*) FROM sp)
ORDER BY g;
INSERT INTO product_image (product_id, s3_url, sort_order)
SELECT p.id, 'https://example.com/perf/' || p.id || '.jpg', 0 FROM product p, base WHERE p.id > base.v;
-- id는 identity라 이어진 번호 (넣는 동안 다른 상품 등록이 없다는 가정, 측정 중 시연 세션은 쉼)
SELECT (v + 1) || ' ' || (v + __PRODUCTS__) || ' ' || (v + __PRODUCTS__ + 1) || ' ' || (v + __PRODUCTS__ + __ORDER_PRODUCTS__) FROM base;
COMMIT;
ANALYZE product; ANALYZE product_image;
SQL
)
sql=${sql//__PRODUCTS__/$PRODUCTS}; sql=${sql//__ORDER_PRODUCTS__/$ORDER_PRODUCTS}
ranges=$(psql_db product_db <<<"$sql")
read -r BROWSE_FIRST BROWSE_LAST ORDER_FIRST ORDER_LAST <<<"$(grep -E '^[0-9]+ [0-9]+ [0-9]+ [0-9]+$' <<<"$ranges")"
step "   둘러보기 상품 $BROWSE_FIRST-$BROWSE_LAST · 주문용 $ORDER_FIRST-$ORDER_LAST"

# ---------- 5. market: 내 주문 목록용 지난 주문 (구매 확정 · 배송 완료, 돈은 건드리지 않음) ----------
step "5. market 지난 주문 $BULK_ORDERS"
sql=$(cat <<'SQL'
BEGIN;
CREATE TEMP TABLE b ON COMMIT DROP AS SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM member_replica WHERE nickname LIKE 'perfbuyer%';
CREATE TEMP TABLE s ON COMMIT DROP AS SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM member_replica WHERE nickname LIKE 'perfseller%';
CREATE TEMP TABLE bulk ON COMMIT DROP AS
SELECT g, b.id AS buyer_id, s.id AS seller_id, ((1 + g % 10) * 10000)::numeric AS price,
       now() - ((g % 365) || ' days')::interval - interval '10 days' AS at
FROM generate_series(1, __BULK__) g
JOIN b ON b.n = 1 + g % (SELECT count(*) FROM b)
JOIN s ON s.n = 1 + (g / 7) % (SELECT count(*) FROM s);
INSERT INTO orders (order_no, buyer_id, total_amount, recipient_name, recipient_phone, zipcode, ship_address1, ship_address2,
                    item_count, status, created_at, updated_at, attempt_no, attempt_count)
SELECT 'PERF-BULK-' || g, buyer_id, price + 3000, '지난구매자', '01000000000', '06236', '서울시 강남구 지난로 1', NULL,
       1, 'COMPLETED', at, at, 1, 1
FROM bulk;
CREATE TEMP TABLE bo ON COMMIT DROP AS SELECT bulk.*, o.id AS order_id FROM bulk JOIN orders o ON o.order_no = 'PERF-BULK-' || bulk.g;
INSERT INTO seller_order (order_id, seller_id, total_amount, settlement_base_amount, shipping_fee, confirmed_at, created_at, updated_at, paid_at)
SELECT order_id, seller_id, price, price, 3000, at + interval '5 days', at, at, at FROM bo;
CREATE TEMP TABLE bso ON COMMIT DROP AS SELECT bo.g, bo.price, bo.at, so.id AS seller_order_id FROM bo JOIN seller_order so ON so.order_id = bo.order_id;
INSERT INTO orders_item (seller_order_id, product_id, product_name, price, status, created_at, updated_at)
SELECT seller_order_id, __PID__, '지난 주문 상품', price, 'CONFIRMED', at, at FROM bso;
INSERT INTO shipment (seller_order_id, courier_order_no, tracking_no, status, item_summary, booked_at, picked_up_at, in_transit_at,
                      delivered_at, version, created_at, updated_at, direction)
SELECT seller_order_id, 'perf-bulk-' || g, 'BULK' || lpad(g::text, 9, '0'), 'DELIVERED', '지난 주문 상품', at, at + interval '1 day',
       at + interval '2 days', at + interval '3 days', 0, at, at + interval '3 days', 'OUTBOUND'
FROM bso;
COMMIT;
ANALYZE orders; ANALYZE seller_order; ANALYZE orders_item; ANALYZE shipment;
SELECT 'orders ' || count(*) FROM orders;
SQL
)
sql=${sql//__BULK__/$BULK_ORDERS}
psql_db market_db <<<"${sql//__PID__/$BROWSE_FIRST}"

step "끝. 다음: 관리자(perf-admin) 토큰으로 POST /api/v1/admin/products/searchIndex 전체 색인"
echo "order.js · mixed.js: -e FIRST_ID=$ORDER_FIRST -e LAST_ID=$ORDER_LAST"
