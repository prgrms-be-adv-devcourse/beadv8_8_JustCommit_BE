-- settlement 서비스 V2: 구매 확정 이벤트(market.events.v1)의 배송비 저장 (#364)
-- 옛 토픽(market.purchase-confirmed.v1)으로 받은 정산과 V2 이전 정산은 배송비를 몰라 null

alter table settlement add column shipping_fee numeric(19, 0);
