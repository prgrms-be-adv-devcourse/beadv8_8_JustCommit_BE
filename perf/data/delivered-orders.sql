-- 가설 1 · 5 분리 측정용: 배송 완료 4일 지난 주문 추가 (#351)
-- 자동 구매확정 스케줄러(perf 프로필 1분 주기)가 다음 실행 때 한꺼번에 확정 → 정산 이벤트 → HOLDING 지갑에서 판매자 지갑으로 이체.
-- 주문 결제와 같은 HOLDING 지갑 행을 잠그므로, 확정이 도는 동안 주문 지연 · 잠금 대기를 본다.
-- seed.sql이 먼저 들어가 있어야 한다. 실행: psql ... -v delivered=3000 -v tag=sched1 < perf/data/delivered-orders.sql
--   seed.sql의 「4. 배송 완료 4일 지난 주문」과 같은 모양 (주문 1건 = 판매자 1명 = 상품 1개, 배송비 3,000원)
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE perf_buyer ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id FROM member WHERE email LIKE 'perf-buyer-%@perf.test';
CREATE TEMP TABLE perf_seller ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM seller s JOIN member m ON m.id = s.member_id
WHERE m.email LIKE 'perf-seller-%@perf.test';
CREATE TEMP TABLE perf_species ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id, name FROM species WHERE NOT is_banned;

DO $$ BEGIN
  IF (SELECT count(*) FROM perf_buyer) = 0 THEN RAISE EXCEPTION 'perf 회원 없음: seed.sql 먼저'; END IF;
END $$;

CREATE TEMP TABLE perf_dlv ON COMMIT DROP AS
SELECT g, b.id AS buyer_id, s.id AS seller_id, sp.id AS species_id, sp.name AS species_name,
       ((1 + g % 10) * 10000)::numeric AS price, NULL::bigint AS product_id, NULL::bigint AS order_id, NULL::bigint AS seller_order_id
FROM generate_series(1, :delivered) g
JOIN perf_buyer b ON b.n = 1 + g % (SELECT count(*) FROM perf_buyer)
JOIN perf_seller s ON s.n = 1 + g % (SELECT count(*) FROM perf_seller)
JOIN perf_species sp ON sp.n = 1 + g % (SELECT count(*) FROM perf_species);

INSERT INTO product (created_at, updated_at, description, height_cm, is_hidden, pot_size, price, seller_id, status, title, species_id)
SELECT now() - interval '8 days', now() - interval '7 days', '배송 완료 주문용', 20, false, 'M', price, seller_id, 'SOLD_OUT',
       'perf-' || :'tag' || '-' || g, species_id
FROM perf_dlv;
UPDATE perf_dlv d SET product_id = p.id FROM product p WHERE p.title = 'perf-' || :'tag' || '-' || d.g;

INSERT INTO orders (created_at, updated_at, buyer_id, item_count, order_no, recipient_name, recipient_phone,
                    ship_address1, ship_address2, status, total_amount, zipcode)
SELECT now() - interval '7 days', now() - interval '7 days', buyer_id, 1, 'PERF-' || upper(:'tag') || '-' || g, '부하구매자',
       '01000000000', '서울시 테스트구 부하로 1', NULL, 'PAID', price + 3000, '00000'
FROM perf_dlv;
UPDATE perf_dlv d SET order_id = o.id FROM orders o WHERE o.order_no = 'PERF-' || upper(:'tag') || '-' || d.g;

INSERT INTO seller_order (created_at, updated_at, confirmed_at, seller_id, settlement_base_amount, shipping_fee, total_amount, order_id)
SELECT now() - interval '7 days', now() - interval '7 days', NULL, seller_id, price, 3000, price, order_id FROM perf_dlv;
UPDATE perf_dlv d SET seller_order_id = so.id FROM seller_order so WHERE so.order_id = d.order_id;

INSERT INTO orders_item (created_at, updated_at, price, product_id, product_name, status, seller_order_id)
SELECT now() - interval '7 days', now() - interval '7 days', price, product_id, species_name, 'NORMAL', seller_order_id FROM perf_dlv;

INSERT INTO shipment (created_at, updated_at, booked_at, picked_up_at, in_transit_at, delivered_at,
                      courier_order_no, tracking_no, item_summary, seller_order_id, status, version)
SELECT now() - interval '7 days', now() - interval '4 days', now() - interval '7 days', now() - interval '6 days',
       now() - interval '5 days', now() - interval '4 days',
       'perf-' || :'tag' || '-' || g, upper(:'tag') || lpad(g::text, 8, '0'), species_name, seller_order_id, 'DELIVERED', 0
FROM perf_dlv;

-- 결제된 돈은 HOLDING에 묶여 있어야 구매확정 때 판매자에게 정산할 수 있다
UPDATE wallet w SET balance = balance + (SELECT coalesce(sum(price + 3000), 0) FROM perf_dlv), updated_at = now()
WHERE w.member_id = (SELECT id FROM member WHERE email = 'system-holding@replant.com');

COMMIT;
SELECT 'delivered orders added' AS what, count(*) FROM orders WHERE order_no LIKE 'PERF-' || upper(:'tag') || '-%';
