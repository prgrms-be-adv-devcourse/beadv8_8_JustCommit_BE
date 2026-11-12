-- 가설 2 분리 측정용 지난 주문 대량 추가 (#351)
-- 구매확정 · 배송 완료된 오래된 주문을 :orders건 넣는다 (주문 1건 = 판매자 1명 = 상품 1개).
-- 돈(지갑 · 정산)은 건드리지 않는다: 목록 조회 속도만 보려는 데이터. 구매확정 스케줄러 대상이 되지 않게 confirmed_at을 채운다.
-- seed.sql이 먼저 들어가 있어야 한다. 실행: psql ... -v orders=100000 < perf/data/bulk-orders.sql
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE perf_buyer ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id FROM member WHERE email LIKE 'perf-buyer-%@perf.test';
CREATE TEMP TABLE perf_seller ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM seller s JOIN member m ON m.id = s.member_id
WHERE m.email LIKE 'perf-seller-%@perf.test';

DO $$ BEGIN
  IF (SELECT count(*) FROM perf_buyer) = 0 OR (SELECT count(*) FROM perf_seller) = 0 THEN
    RAISE EXCEPTION 'perf 회원 없음: seed.sql 먼저';
  END IF;
  IF EXISTS (SELECT 1 FROM orders WHERE order_no LIKE 'PERF-BULK-%') THEN
    RAISE EXCEPTION '이미 PERF-BULK 주문이 있음';
  END IF;
END $$;

-- 상품은 이미 팔린 테스트 상품 하나를 가리킨다 (orders_item.product_id에는 외래키가 없고, 목록은 상품명 · 가격을 주문 쪽 값으로 보여준다)
CREATE TEMP TABLE perf_bulk ON COMMIT DROP AS
SELECT g, b.id AS buyer_id, s.id AS seller_id, ((1 + g % 10) * 10000)::numeric AS price,
       now() - ((g % 365) || ' days')::interval - interval '10 days' AS at
FROM generate_series(1, :orders) g
JOIN perf_buyer b ON b.n = 1 + g % (SELECT count(*) FROM perf_buyer)
JOIN perf_seller s ON s.n = 1 + (g / 7) % (SELECT count(*) FROM perf_seller);

INSERT INTO orders (created_at, updated_at, buyer_id, item_count, order_no, recipient_name, recipient_phone,
                    ship_address1, ship_address2, status, total_amount, zipcode)
SELECT at, at, buyer_id, 1, 'PERF-BULK-' || g, '지난구매자', '01000000000', '서울시 테스트구 지난로 1', NULL,
       'COMPLETED', price + 3000, '00000'
FROM perf_bulk;

CREATE TEMP TABLE perf_bulk_ids ON COMMIT DROP AS
SELECT b.g, b.seller_id, b.price, b.at, o.id AS order_id
FROM perf_bulk b JOIN orders o ON o.order_no = 'PERF-BULK-' || b.g;

INSERT INTO seller_order (created_at, updated_at, confirmed_at, seller_id, settlement_base_amount, shipping_fee, total_amount, order_id)
SELECT at, at, at + interval '5 days', seller_id, price, 3000, price, order_id FROM perf_bulk_ids;

CREATE TEMP TABLE perf_bulk_so ON COMMIT DROP AS
SELECT i.g, i.price, i.at, so.id AS seller_order_id
FROM perf_bulk_ids i JOIN seller_order so ON so.order_id = i.order_id;

INSERT INTO orders_item (created_at, updated_at, price, product_id, product_name, status, seller_order_id)
SELECT at, at, price, (SELECT min(id) FROM product WHERE title LIKE 'perf-delivered-%'), '지난 주문 상품', 'NORMAL', seller_order_id
FROM perf_bulk_so;

INSERT INTO shipment (created_at, updated_at, booked_at, picked_up_at, in_transit_at, delivered_at,
                      courier_order_no, tracking_no, item_summary, seller_order_id, status, version)
SELECT at, at + interval '3 days', at, at + interval '1 day', at + interval '2 days', at + interval '3 days',
       'perf-bulk-' || g, 'BULK' || lpad(g::text, 9, '0'), '지난 주문 상품', seller_order_id, 'DELIVERED', 0
FROM perf_bulk_so;

COMMIT;
ANALYZE orders; ANALYZE seller_order; ANALYZE orders_item; ANALYZE shipment;

SELECT 'bulk orders' AS what, count(*) FROM orders WHERE order_no LIKE 'PERF-BULK-%'
UNION ALL SELECT 'orders_item total', count(*) FROM orders_item;
