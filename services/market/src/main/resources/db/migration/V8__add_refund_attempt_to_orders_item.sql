-- 취소 중 주문상품의 환불 시도 (#282, ADR 0026). 상태 칸은 문자열이라 CANCEL_PROCESSING, REVIEW_REQUIRED에 스키마 변경은 필요 없다.
-- attempt_count: 환불 복구 선점 횟수. 첫 환불 호출은 세지 않음
-- attempted_at: 마지막 환불 시도 시각 (market 앱 시각). 비어 있으면 택배 취소 결과를 기다리는 중
-- last_error: 환불 결과 불명, 확정 실패 원인 코드 (TIMEOUT, HTTP_503, CONNECTION_FAILED 등)
alter table orders_item
    add column attempt_count integer not null default 0,
    add column attempted_at  timestamp(6),
    add column last_error    varchar(100);

-- Saga 복구 후보(환불 재요청) 조회용
create index idx_orders_item_cancel_processing_attempted_at
    on orders_item (attempted_at)
    where status = 'CANCEL_PROCESSING';
