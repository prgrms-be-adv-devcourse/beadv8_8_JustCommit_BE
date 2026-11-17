-- market V5: 택배 예약 큐 (#475)
-- 결제 커밋 직후 요청 스레드에서 택배사를 부르던 예약을 10초 큐로 옮긴다 (docs/reference/schedulers.md 「택배 예약 큐」)
alter table shipment
    -- 큐가 이 배송을 다음에 집을 수 있는 시각. 비어 있으면 바로 집는다.
    -- 큐가 집을 때 잠깐 뒤로 미뤄(임대) 다른 파드가 같은 배송을 다시 집지 않게 하고, 실패하면 10초 → 30초 → 1분 뒤로 둔다
    add column next_attempt_at timestamp(6),
    -- 택배 예약에 실패한 횟수. 다음 시도까지 기다릴 시간을 정할 때 쓴다
    add column attempt_count   integer not null default 0;

-- 예약 큐가 READY 배송 중 시도할 때가 된 것만 찾는다
create index idx_shipment_ready_next_attempt_at on shipment (next_attempt_at) where status = 'READY';
