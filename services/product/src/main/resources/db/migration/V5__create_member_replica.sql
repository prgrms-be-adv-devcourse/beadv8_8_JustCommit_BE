-- 회원 복제본 (member.events.v1 구독, docs/flows/signup-and-replica.md)
-- 상품 등록 때 판매자 확인 · 상품 상세의 판매자 닉네임에 쓴다. 계좌 · 연락처는 복제하지 않는다
-- last_event_at: 마지막으로 반영한 이벤트의 발생 시각. 이보다 오래된 이벤트는 무시한다 (순서가 바뀌어 와도 맞게)
-- 탈퇴(withdrawn)는 되돌리지 않는다. 탈퇴 이벤트가 가입 이벤트보다 먼저 오면 닉네임 · 권한 없이 행이 생긴다
-- role에는 CHECK를 두지 않는다 (V1과 같은 규칙)

create table member_replica (
    member_id     bigint      not null,
    nickname      varchar(50),
    role          varchar(20),
    withdrawn     boolean     not null default false,
    last_event_at timestamptz not null,
    created_at    timestamp(6),
    updated_at    timestamp(6),
    constraint pk_member_replica primary key (member_id)
);
