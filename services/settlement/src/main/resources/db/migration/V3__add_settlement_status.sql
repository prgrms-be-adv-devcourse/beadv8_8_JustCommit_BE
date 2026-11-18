-- settlement 서비스 V3: 정산 지급 상태와 재시도 칸 (#427)
-- 지급을 정산 지급 스케줄러로 옮기기 위해 상태를 둔다.
-- V3 이전 정산은 저장과 지급을 한 트랜잭션에서 했으므로 남아 있는 행은 모두 지급된 것 → COMPLETED, completed_at = settled_at

alter table settlement add column status          varchar(20);
alter table settlement add column attempt_count   integer not null default 0;
alter table settlement add column next_attempt_at timestamp(6);
alter table settlement add column last_error      varchar(500);

-- 정산 일시(settled_at) → 지급 완료 또는 회수 요청 성공 시각(completed_at). 완료 전 정산은 null
alter table settlement rename column settled_at to completed_at;
alter table settlement alter column completed_at drop not null;

update settlement set status = 'COMPLETED';
alter table settlement alter column status set not null;

-- 완료 시각은 COMPLETED일 때만 있다
alter table settlement add constraint ck_settlement_completed_at
    check ((status = 'COMPLETED') = (completed_at is not null));

-- 판매자 정산 내역 (findBySellerIdOrderByCreatedAtDesc): 완료 전 정산은 completed_at이 없어 생성 시각으로 정렬
drop index idx_settlement_seller_id_settled_at;
create index idx_settlement_seller_id_created_at on settlement (seller_id, created_at desc);

-- 정산 지급 스케줄러: 끝나지 않은 정산만 본다
create index idx_settlement_payout_due on settlement (next_attempt_at)
    where status in ('PENDING', 'FAILED');
