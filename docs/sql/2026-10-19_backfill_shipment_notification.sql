-- 예전 알림 데이터 보정과 배송 복사 컬럼 정리 (#199, #204)
-- 운영에서 한 번 실행한다. 서버 배포(#204 포함) 후에 실행한다.
--
-- 1) 알림 보정: 컬럼이 추가되기 전에 만든 알림은 order_id, item_summary가 비어 있다.
--    order_id     비면 알림에서 주문 상세로 이동 불가 → 판매자 주문에서 채운다
--    item_summary 비면 상품명 스티커 없음 → 배송에 저장된 상품 요약에서 채운다
--    비어 있는 값만 채우므로 여러 번 실행해도 결과가 같다.
--
-- 2) 배송 복사 컬럼 삭제: #204부터 주문 정보는 seller_order_id로 조회하므로 아래 컬럼을 쓰지 않는다.
--    ddl-auto: update는 컬럼을 지우지 않아 남아 있어도 동작에는 영향이 없지만, 헷갈리지 않게 정리한다.

BEGIN;

-- 0. 보정 전 빈 값 개수
SELECT count(*) FILTER (WHERE order_id IS NULL AND seller_order_id IS NOT NULL) AS order_id_null,
       count(*) FILTER (WHERE item_summary IS NULL AND seller_order_id IS NOT NULL) AS item_summary_null
FROM notification;

-- 1. 알림 보정
UPDATE notification n
SET order_id = so.order_id
FROM seller_order so
WHERE so.id = n.seller_order_id
  AND n.order_id IS NULL;

UPDATE notification n
SET item_summary = s.item_summary
FROM shipment s
WHERE s.seller_order_id = n.seller_order_id
  AND n.item_summary IS NULL;

-- 2. 배송 복사 컬럼 삭제 (없으면 건너뜀)
ALTER TABLE shipment
    DROP COLUMN IF EXISTS order_id,
    DROP COLUMN IF EXISTS buyer_id,
    DROP COLUMN IF EXISTS seller_id,
    DROP COLUMN IF EXISTS shipping_fee,
    DROP COLUMN IF EXISTS receiver_name,
    DROP COLUMN IF EXISTS receiver_phone,
    DROP COLUMN IF EXISTS receiver_address;

-- 3. 보정 후 빈 값 개수 (order_id는 0이어야 한다. item_summary는 배송에도 값이 없던 아주 오래된 알림만 null로 남는다)
SELECT count(*) FILTER (WHERE order_id IS NULL AND seller_order_id IS NOT NULL) AS order_id_null,
       count(*) FILTER (WHERE item_summary IS NULL AND seller_order_id IS NOT NULL) AS item_summary_null
FROM notification;

-- 결과가 맞으면 COMMIT, 이상하면 ROLLBACK
COMMIT;
