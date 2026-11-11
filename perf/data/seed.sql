-- 부하 측정용 테스트 데이터 (#351) — 세미 모놀리스 스키마 기준
-- 앱이 한 번 떠서 테이블 · 시스템 계정(HOLDING 등) · 품종이 들어간 뒤에 실행한다.
-- 비밀번호는 레포에 남기지 않는다: 실행할 때 bcrypt 해시를 pw_hash로 넘긴다 (perf/data/README.md).
--
-- 만드는 것
--   구매자 :buyers명 (지갑 잔액 1억, 잔액 부족으로 주문이 PAYMENT_PENDING에 묶이지 않게)
--   판매자 :sellers명 (판매자 등록 + 기본 배송지(수거지) + 지갑 0원)
--   판매 중 상품 :products개 (품종 · 판매자 · 가격을 고르게 섞음, 사진 1장)
--   배송 완료 4일 지난 주문 :delivered건 (자동 구매확정 · 정산 대상, HOLDING 잔액도 맞춤)
-- 테스트 계정 이메일: perf-buyer-{n}@perf.test · perf-seller-{n}@perf.test

\set ON_ERROR_STOP on
BEGIN;

-- 0. 계좌번호는 서버 키로 암호화돼 있어야 복호화된다 → 앱이 만든 시스템 계정의 값을 그대로 쓴다
CREATE TEMP TABLE perf_tpl ON COMMIT DROP AS
SELECT account_no_enc, bank_code FROM member WHERE role = 'SYSTEM' ORDER BY id LIMIT 1;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM perf_tpl) THEN RAISE EXCEPTION '시스템 계정이 없음: 앱을 먼저 한 번 띄울 것'; END IF;
  IF EXISTS (SELECT 1 FROM member WHERE email LIKE 'perf-%@perf.test') THEN RAISE EXCEPTION '이미 테스트 데이터가 있음: 백업을 복원한 뒤 실행할 것'; END IF;
END $$;

-- 1. 회원
INSERT INTO member (created_at, updated_at, account_holder, account_no_enc, account_updated_at, bank_code,
                    email, nickname, password, phone, provider, role, status)
SELECT now(), now(), '부하구매자' || g, t.account_no_enc, now(), t.bank_code,
       'perf-buyer-' || g || '@perf.test', 'perfbuyer' || g, :'pw_hash', '0109' || lpad(g::text, 7, '0'),
       'LOCAL', 'MEMBER', 'ACTIVE'
FROM generate_series(1, :buyers) g, perf_tpl t;

INSERT INTO member (created_at, updated_at, account_holder, account_no_enc, account_updated_at, bank_code,
                    email, nickname, password, phone, provider, role, status)
SELECT now(), now(), '부하판매자' || g, t.account_no_enc, now(), t.bank_code,
       'perf-seller-' || g || '@perf.test', 'perfseller' || g, :'pw_hash', '0108' || lpad(g::text, 7, '0'),
       'LOCAL', 'SELLER', 'ACTIVE'
FROM generate_series(1, :sellers) g, perf_tpl t;

INSERT INTO seller (member_id, created_at, updated_at, intro)
SELECT id, now(), now(), '부하 테스트 판매자' FROM member WHERE email LIKE 'perf-seller-%@perf.test';

-- 판매자 기본 배송지: 택배 예약 때 수거 주소로 쓴다 (없으면 주문 후 택배 예약이 실패해 READY에 머묾)
INSERT INTO address (created_at, updated_at, address1, address2, address_name, is_default, recipient_name, recipient_phone, zipcode, member_id)
SELECT now(), now(), '서울시 테스트구 판매로 ' || m.id, NULL, '수거지', true, m.account_holder, m.phone, '00000', m.id
FROM member m WHERE m.email LIKE 'perf-seller-%@perf.test';

-- 2. 지갑 (구매자 1억, 판매자 0원)
INSERT INTO wallet (created_at, updated_at, balance, member_id, version)
SELECT now(), now(), CASE WHEN role = 'MEMBER' THEN 100000000 ELSE 0 END, id, 0
FROM member WHERE email LIKE 'perf-%@perf.test';

-- 번호 → id 표 (아래에서 고르게 나눠 주기용)
CREATE TEMP TABLE perf_buyer ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id FROM member WHERE email LIKE 'perf-buyer-%@perf.test';
CREATE TEMP TABLE perf_seller ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM seller s JOIN member m ON m.id = s.member_id
WHERE m.email LIKE 'perf-seller-%@perf.test';
CREATE TEMP TABLE perf_species ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id, name FROM species WHERE NOT is_banned;

-- 3. 판매 중 상품 + 사진 (가격 1만 ~ 10만, 판매자당 :products / :sellers개)
INSERT INTO product (created_at, updated_at, description, grow_note, height_cm, is_hidden, pot_size, price,
                     seller_id, status, title, species_id)
SELECT now() - (g || ' minutes')::interval, now(), sp.name || ' 부하 테스트 상품입니다.', NULL, 10 + g % 50, false, 'M',
       (1 + g % 10) * 10000, s.id, 'ACTIVE', sp.name || ' ' || g, sp.id
FROM generate_series(1, :products) g
JOIN perf_seller s ON s.n = 1 + g % (SELECT count(*) FROM perf_seller)
JOIN perf_species sp ON sp.n = 1 + g % (SELECT count(*) FROM perf_species);

INSERT INTO product_picture (s3_url, sort_order, product_id)
SELECT 'https://example.com/perf/' || p.id || '.jpg', 0, p.id
FROM product p JOIN member m ON m.id = p.seller_id
WHERE m.email LIKE 'perf-seller-%@perf.test';

-- 4. 배송 완료 4일 지난 주문 (주문 1건 = 판매자 1명 = 상품 1개, 배송비 3,000원)
CREATE TEMP TABLE perf_dlv ON COMMIT DROP AS
SELECT g, b.id AS buyer_id, s.id AS seller_id, sp.id AS species_id, sp.name AS species_name,
       ((1 + g % 10) * 10000)::numeric AS price, NULL::bigint AS product_id, NULL::bigint AS order_id, NULL::bigint AS seller_order_id
FROM generate_series(1, :delivered) g
JOIN perf_buyer b ON b.n = 1 + g % (SELECT count(*) FROM perf_buyer)
JOIN perf_seller s ON s.n = 1 + g % (SELECT count(*) FROM perf_seller)
JOIN perf_species sp ON sp.n = 1 + g % (SELECT count(*) FROM perf_species);

INSERT INTO product (created_at, updated_at, description, height_cm, is_hidden, pot_size, price, seller_id, status, title, species_id)
SELECT now() - interval '8 days', now() - interval '7 days', '배송 완료 주문용', 20, false, 'M', price, seller_id, 'SOLD_OUT',
       'perf-delivered-' || g, species_id
FROM perf_dlv;
UPDATE perf_dlv d SET product_id = p.id FROM product p WHERE p.title = 'perf-delivered-' || d.g;

INSERT INTO orders (created_at, updated_at, buyer_id, item_count, order_no, recipient_name, recipient_phone,
                    ship_address1, ship_address2, status, total_amount, zipcode)
SELECT now() - interval '7 days', now() - interval '7 days', buyer_id, 1, 'PERF-DLV-' || g, '부하구매자', '01000000000',
       '서울시 테스트구 부하로 1', NULL, 'PAID', price + 3000, '00000'
FROM perf_dlv;
UPDATE perf_dlv d SET order_id = o.id FROM orders o WHERE o.order_no = 'PERF-DLV-' || d.g;

INSERT INTO seller_order (created_at, updated_at, confirmed_at, seller_id, settlement_base_amount, shipping_fee, total_amount, order_id)
SELECT now() - interval '7 days', now() - interval '7 days', NULL, seller_id, price, 3000, price, order_id FROM perf_dlv;
UPDATE perf_dlv d SET seller_order_id = so.id FROM seller_order so WHERE so.order_id = d.order_id;

INSERT INTO orders_item (created_at, updated_at, price, product_id, product_name, status, seller_order_id)
SELECT now() - interval '7 days', now() - interval '7 days', price, product_id, species_name, 'NORMAL', seller_order_id FROM perf_dlv;

INSERT INTO shipment (created_at, updated_at, booked_at, picked_up_at, in_transit_at, delivered_at,
                      courier_order_no, tracking_no, item_summary, seller_order_id, status, version)
SELECT now() - interval '7 days', now() - interval '4 days', now() - interval '7 days', now() - interval '6 days',
       now() - interval '5 days', now() - interval '4 days',
       'perf-dlv-' || g, 'PERF' || lpad(g::text, 8, '0'), species_name, seller_order_id, 'DELIVERED', 0
FROM perf_dlv;

-- 결제된 돈은 HOLDING에 묶여 있어야 구매확정 때 판매자에게 정산할 수 있다
UPDATE wallet w SET balance = balance + (SELECT coalesce(sum(price + 3000), 0) FROM perf_dlv), updated_at = now()
WHERE w.member_id = (SELECT id FROM member WHERE email = 'system-holding@replant.com');

COMMIT;

-- 결과
SELECT 'buyers' AS what, count(*) FROM member WHERE email LIKE 'perf-buyer-%'
UNION ALL SELECT 'sellers', count(*) FROM seller s JOIN member m ON m.id = s.member_id WHERE m.email LIKE 'perf-seller-%'
UNION ALL SELECT 'active products', count(*) FROM product WHERE status = 'ACTIVE' AND description LIKE '%부하 테스트%'
UNION ALL SELECT 'delivered orders', count(*) FROM orders WHERE order_no LIKE 'PERF-DLV-%';
