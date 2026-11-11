-- market V2: 회원 복제본 (member.events.v1로 채움, #275)
-- 판매자 기본 배송지 칸은 member.address.events.v1을 받을 때 다음 버전으로 추가한다.
create table member_replica (
    member_id     bigint                      not null,
    -- 탈퇴 이벤트가 가입 이벤트보다 먼저 오면 닉네임 · 권한을 모르므로 비워 둘 수 있게
    nickname      varchar(20),
    role          varchar(20),
    seller        boolean                     not null default false,
    -- 행을 지우지 않고 표시만 함: 늦게 온 가입 이벤트가 탈퇴 회원을 되살리지 못하게
    withdrawn     boolean                     not null default false,
    -- 마지막으로 반영한 이벤트의 occurredAt. 이보다 오래된 이벤트는 무시
    last_event_at timestamp(6) with time zone not null,
    updated_at    timestamp(6) with time zone not null,
    constraint pk_member_replica primary key (member_id)
);
