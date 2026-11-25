#!/usr/bin/env bash
# 동시 구매 결과 확인 (#628) — 세미 check-concurrent.sql의 MSA판. DB가 나뉘어 있어 product · market · payment를 따로 보고 맞춘다
# 사용: perf/data/msa/check-concurrent.sh <FIRST_ID> [ROUNDS=10]
# 통과: 상품마다 PAID 이후 상태 주문이 정확히 1건, 그 주문마다 구매자 지갑 PAYMENT 출금이 1건 · 금액 = 주문 총액
set -euo pipefail
first=${1:?FIRST_ID}; rounds=${2:-10}; last=$((first + rounds - 1))
DATA=${DATA_HOST:-replant-data}
psql_db() { ssh -o BatchMode=yes "$DATA" "docker exec -i replant-postgres sh -c 'psql -U \$POSTGRES_USER -d $1 -v ON_ERROR_STOP=1 -P pager=off'"; }

echo "== product: 상품 상태 (SOLD_OUT 또는 RESERVED여야 함)"
psql_db product_db <<<"SELECT id, status, reserved_order_id FROM product WHERE id BETWEEN $first AND $last ORDER BY id;"

echo "== market: 상품별 결제된 주문 수 (paid_orders = 1이어야 함)"
psql_db market_db <<<"
SELECT oi.product_id,
       count(*) FILTER (WHERE o.status IN ('PAID', 'COMPLETED')) AS paid_orders,
       count(*) FILTER (WHERE o.status NOT IN ('PAID', 'COMPLETED')) AS other_orders,
       string_agg(DISTINCT o.status, ',') AS statuses
FROM orders_item oi JOIN seller_order so ON so.id = oi.seller_order_id JOIN orders o ON o.id = so.order_id
WHERE oi.product_id BETWEEN $first AND $last GROUP BY oi.product_id ORDER BY oi.product_id;"

paid=$(psql_db market_db <<<"\\pset format unaligned
\\pset tuples_only on
SELECT o.id || ',' || o.buyer_id || ',' || o.total_amount
FROM orders_item oi JOIN seller_order so ON so.id = oi.seller_order_id JOIN orders o ON o.id = so.order_id
WHERE oi.product_id BETWEEN $first AND $last AND o.status IN ('PAID', 'COMPLETED');" | grep -E '^[0-9]+,')

echo "== payment: 결제된 주문마다 구매자 지갑 출금 (rows = 1, 금액 = 주문 총액이어야 함)"
{
  printf '%s\n' "CREATE TEMP TABLE p (order_id bigint, buyer_id bigint, total numeric);" "COPY p FROM STDIN WITH (FORMAT csv);"
  printf '%s\n' "$paid" '\.'
  printf '%s\n' "SELECT p.order_id, p.total, count(wt.id) AS payment_rows, coalesce(sum(wt.amount), 0) AS payment_sum,
       CASE WHEN count(wt.id) = 1 AND sum(wt.amount) = p.total THEN 'OK' ELSE 'MISMATCH' END AS result
FROM p LEFT JOIN wallet w ON w.member_id = p.buyer_id AND w.wallet_type = 'MEMBER'
LEFT JOIN wallet_transaction wt ON wt.wallet_id = w.id AND wt.ref_type = 'PAYMENT' AND wt.status = 'OUT' AND wt.ref_id = p.order_id
GROUP BY p.order_id, p.total ORDER BY p.order_id;"
} | psql_db payment_db
