-- 결제 시도를 먼저 커밋하고 트랜잭션 밖에서 결제를 부른다 (ADR 0023, #281)
-- attempt_no: 시도 번호. 결제 시작(TX2) · 복구 선점 · REVIEW_REQUIRED 전환 때 +1. 결과는 자기 번호일 때만 반영
-- attempt_count: 복구 선점 횟수. 첫 결제 호출은 세지 않음
-- attempted_at: 마지막 시도 시각 (market 앱 시각). 복구는 이 시각에서 유예가 지난 주문만 집는다
-- last_error: 결과 불명 · 확정 실패 원인 코드 (TIMEOUT, HTTP_503, CONNECTION_FAILED 등)
alter table orders
    add column attempt_no    integer not null default 0,
    add column attempt_count integer not null default 0,
    add column attempted_at  timestamp(6),
    add column last_error    varchar(100);

-- Saga 복구 후보 조회용
create index idx_orders_processing_attempted_at
    on orders (attempted_at)
    where status = 'PAYMENT_PROCESSING';
