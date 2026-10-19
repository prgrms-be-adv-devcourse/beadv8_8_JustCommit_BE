-- 예전 배송·알림 데이터 보정 (#199)
-- 배송·알림에 컬럼이 추가되기 전에 만든 행은 값이 비어 있어 오류가 난다.
--   shipment.version      비면 웹훅·취소 시 저장 실패 (@Version)
--   shipment.shipping_fee 비면 수거 시 정산 쪽 배송비 수납 예외
--   shipment.seller_id    비면 READY 배송 재예약 실패
--   shipment.buyer_id     비면 구매자 배송 알림 누락
--   shipment.order_id, notification.order_id      비면 알림에서 주문 상세로 이동 불가
--   notification.item_summary                     비면 알림 상품명 스티커 없음
--
-- 비어 있는 값만 채우므로 여러 번 실행해도 결과가 같다.
-- 순서: 배송을 먼저 채운 뒤 알림을 배송에서 복사한다.
-- 받는 사람 정보(receiver_*)는 READY 배송 재예약에만 쓰여 제외한다.

BEGIN;

-- 0. 보정 전 빈 값 개수
SELECT 'shipment' AS tbl,
       count(*) FILTER (WHERE version IS NULL)      AS version_null,
       count(*) FILTER (WHERE order_id IS NULL)     AS order_id_null,
       count(*) FILTER (WHERE seller_id IS NULL)    AS seller_id_null,
       count(*) FILTER (WHERE buyer_id IS NULL)     AS buyer_id_null,
       count(*) FILTER (WHERE shipping_fee IS NULL) AS shipping_fee_null,
       count(*) FILTER (WHERE item_summary IS NULL) AS item_summary_null
FROM shipment;

SELECT 'notification' AS tbl,
       count(*) FILTER (WHERE order_id IS NULL AND seller_order_id IS NOT NULL) AS order_id_null,
       count(*) FILTER (WHERE item_summary IS NULL)                             AS item_summary_null
FROM notification;

-- 1. 배송
UPDATE shipment SET version = 0 WHERE version IS NULL;

UPDATE shipment s
SET order_id     = COALESCE(s.order_id, so.order_id),
    seller_id    = COALESCE(s.seller_id, so.seller_id),
    shipping_fee = COALESCE(s.shipping_fee, so.shipping_fee)
FROM seller_order so
WHERE so.id = s.seller_order_id
  AND (s.order_id IS NULL OR s.seller_id IS NULL OR s.shipping_fee IS NULL);

UPDATE shipment s
SET buyer_id = o.buyer_id
FROM seller_order so
JOIN orders o ON o.id = so.order_id
WHERE so.id = s.seller_order_id
  AND s.buyer_id IS NULL;

-- 2. 알림 (배송에서 복사)
UPDATE notification n
SET order_id     = COALESCE(n.order_id, s.order_id),
    item_summary = COALESCE(n.item_summary, s.item_summary)
FROM shipment s
WHERE s.seller_order_id = n.seller_order_id
  AND (n.order_id IS NULL OR n.item_summary IS NULL);

-- 3. 보정 후 빈 값 개수 (0 확인. 배송 item_summary가 원래 비어 있던 아주 오래된 행은 알림도 null로 남는다)
SELECT 'shipment' AS tbl,
       count(*) FILTER (WHERE version IS NULL)      AS version_null,
       count(*) FILTER (WHERE order_id IS NULL)     AS order_id_null,
       count(*) FILTER (WHERE seller_id IS NULL)    AS seller_id_null,
       count(*) FILTER (WHERE buyer_id IS NULL)     AS buyer_id_null,
       count(*) FILTER (WHERE shipping_fee IS NULL) AS shipping_fee_null
FROM shipment;

SELECT 'notification' AS tbl,
       count(*) FILTER (WHERE order_id IS NULL AND seller_order_id IS NOT NULL) AS order_id_null,
       count(*) FILTER (WHERE item_summary IS NULL)                             AS item_summary_null
FROM notification;

-- 결과가 맞으면 COMMIT, 이상하면 ROLLBACK
COMMIT;
