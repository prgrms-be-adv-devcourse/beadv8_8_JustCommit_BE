-- 동시 구매 결과 확인 (#351): 상품마다 결제된 주문이 정확히 1건이고 돈이 맞는지
-- 실행: psql ... -v first_id=<FIRST_ID> -v rounds=10 < perf/data/check-concurrent.sql
\set ON_ERROR_STOP on

-- 1. 상품별: 상태 · 결제된(PAID 이후 상태) 주문 수 · 전체 주문 시도 수
SELECT p.id AS product_id, p.status AS product_status,
       count(*) FILTER (WHERE o.status <> 'PENDING' AND o.status <> 'CANCELLED') AS paid_orders,
       count(o.id) AS order_rows
FROM product p
LEFT JOIN orders_item oi ON oi.product_id = p.id
LEFT JOIN seller_order so ON so.id = oi.seller_order_id
LEFT JOIN orders o ON o.id = so.order_id
WHERE p.id BETWEEN :first_id AND :first_id + :rounds - 1
GROUP BY p.id, p.status ORDER BY p.id;

-- 2. 결제된 주문마다 구매자 지갑 출금이 1번씩 기록됐는지 (중복 출금 · 빠진 출금 찾기)
SELECT o.id AS order_id, o.status, o.total_amount,
       count(wt.id) AS buyer_payment_rows, coalesce(sum(wt.amount), 0) AS buyer_payment_sum
FROM orders o
JOIN seller_order so ON so.order_id = o.id
JOIN orders_item oi ON oi.seller_order_id = so.id
LEFT JOIN wallet w ON w.member_id = o.buyer_id
LEFT JOIN wallet_transaction wt ON wt.wallet_id = w.id AND wt.ref_type = 'PAYMENT' AND wt.ref_id = o.id
WHERE oi.product_id BETWEEN :first_id AND :first_id + :rounds - 1
GROUP BY o.id, o.status, o.total_amount ORDER BY o.id;
