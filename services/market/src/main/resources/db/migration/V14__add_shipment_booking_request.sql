-- market V14: 택배 예약 첫 요청 본문 고정 (#672)
-- 택배사는 같은 멱등 키(courier_order_no)에 다른 본문이 오면 409 IDEMPOTENCY_KEY_REUSED로 거절한다.
-- 첫 시도 직전에 보낼 본문을 저장해 두고 재시도는 이 값을 그대로 보낸다 (그사이 판매자 배송지가 바뀌어도 같은 본문).
-- 판매자 주소가 들어 있어 평문으로 저장하지 않는다: 판매자 배송지 복호화 키(messaging.address-event-cipher)로 암호화한 문자열.
-- 예약이 끝나거나 취소로 큐를 떠나면 비운다.
alter table shipment
    add column booking_request text;
