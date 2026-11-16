-- payment 서비스 V2: 결과 불명 충전(PENDING) 복구 스케줄러 칸 (#363)
-- attempt_count: 토스 결제 조회 실패 횟수 (정상 보류와 키 오류는 세지 않음)
-- next_attempt_at: 다음 조회 시각. PENDING이 아니면 null
-- status에 REVIEW_REQUIRED가 추가되지만 enum 칸에는 CHECK를 두지 않는다 (V1과 같은 규칙)

alter table charge add column attempt_count   integer not null default 0;
alter table charge add column next_attempt_at timestamp(6);

-- V2 이전에 PENDING으로 남은 충전도 다음 실행에서 바로 조회 대상이 되게 한다
update charge set next_attempt_at = updated_at where status = 'PENDING';

-- 복구 스케줄러: PENDING 충전만 본다
create index idx_charge_recovery_due on charge (next_attempt_at) where status = 'PENDING';
