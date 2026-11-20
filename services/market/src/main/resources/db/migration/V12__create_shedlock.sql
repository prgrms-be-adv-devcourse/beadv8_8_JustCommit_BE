-- ShedLock 잠금 테이블 (#589). 행이 아닌 「한 번만 할 일」(요금표 버전 대사)을 파드 2개 중 한 곳에서만 돌린다.
-- 형식은 ShedLock JdbcTemplateLockProvider 기본값 그대로
create table shedlock (
    name       varchar(64)  not null,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null,
    constraint pk_shedlock primary key (name)
);
