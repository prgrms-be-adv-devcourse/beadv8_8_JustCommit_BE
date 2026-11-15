-- 가설 2 분리 측정 (#351): 목록 조회가 거르는 컬럼에 인덱스 추가 (세미 코드에는 없음)
--   orders_item.seller_order_id : 판매자 주문 · 주문 상세에서 상품 줄 찾기 (findBySellerOrderIds)
--   seller_order.seller_id      : 판매자 주문 목록 (findBySellerId) — 지금 있는 (order_id, seller_id) 유니크는 order_id가 앞이라 못 씀
--   orders.buyer_id             : 내 주문 목록 (findByBuyerId)
-- 되돌리기: DROP INDEX perf_idx_orders_item_seller_order, perf_idx_seller_order_seller, perf_idx_orders_buyer;
CREATE INDEX IF NOT EXISTS perf_idx_orders_item_seller_order ON orders_item (seller_order_id);
CREATE INDEX IF NOT EXISTS perf_idx_seller_order_seller ON seller_order (seller_id);
CREATE INDEX IF NOT EXISTS perf_idx_orders_buyer ON orders (buyer_id);
ANALYZE orders; ANALYZE seller_order; ANALYZE orders_item;
