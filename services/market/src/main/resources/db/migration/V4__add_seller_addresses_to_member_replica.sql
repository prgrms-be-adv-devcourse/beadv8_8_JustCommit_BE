-- market V4: 판매자 배송지 복제본 칸 (member.address.events.v1로 채움, #275)
-- 주소만 먼저 와서 행을 만들 때 회원 칸은 비어 있고 last_event_at은 1970-01-01로 둔다 (저장소 쿼리 참고)
alter table member_replica
    -- 받은 배송지 목록 암호문 그대로 (v1.k1:...). 복호화는 쓸 때만 한다.
    -- 탈퇴 이벤트 반영 후에는 비우고, 늦게 도착한 주소 이벤트로 다시 채우지 않는다
    add column seller_addresses_enc      text,
    -- 마지막으로 반영한 주소 이벤트의 occurredAt (마이크로초). 회원 이벤트 시각(last_event_at)과 따로 비교한다
    add column seller_addresses_event_at timestamp(6) with time zone;