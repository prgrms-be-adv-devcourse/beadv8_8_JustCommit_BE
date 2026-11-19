-- 판매자 수락 · 거절 (#284, ADR 0013). 주문상품 상태 칸은 문자열이라 ACCEPT_PENDING에 스키마 변경은 필요 없다.
-- paid_at: 결제 완료 시각 (재결제면 재결제 완료 시각, market 앱 시각). 수락 마감 = paid_at + 24시간.
--          이 칸이 생기기 전에 결제된 판매자 주문은 비어 있고, 그 주문상품은 이미 NORMAL(수락됨)이다
alter table seller_order
    add column paid_at timestamp(6);

-- cancel_reason: 취소 사유 BUYER(구매자 취소) · SELLER(판매자 거절) · AUTO(자동 거절).
-- 환불 결과를 모를 때 Saga 복구가 이 값으로 판매 재개 이벤트(market.order-item-cancelled.v1)의 reason을 정한다
alter table orders_item
    add column cancel_reason varchar(20);

-- 이 칸이 생기기 전의 취소는 모두 구매자 취소였다 (판매자 거절이 없었음)
update orders_item
   set cancel_reason = 'BUYER'
 where status in ('CANCEL_PROCESSING', 'CANCELLED', 'REVIEW_REQUIRED');
