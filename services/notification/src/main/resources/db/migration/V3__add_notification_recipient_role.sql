-- 역할 기준 알림 (#561): 관리자 검수 지연처럼 한 사람이 아닌 역할 전체가 받는 알림은
-- member_id 없이 recipient_role(ADMIN)로 1건만 저장하고, 그 역할 회원 누구나 보고 읽음은 함께 쓴다
alter table notification alter column member_id drop not null;
alter table notification add column recipient_role varchar(20);
-- 받는 사람(회원 또는 역할)은 반드시 하나 있어야 한다
alter table notification add constraint ck_notification_recipient
    check (member_id is not null or recipient_role is not null);

-- 역할 알림 목록 · 안 읽은 개수
create index idx_notification_role_read_created on notification (recipient_role, is_read, created_at);
