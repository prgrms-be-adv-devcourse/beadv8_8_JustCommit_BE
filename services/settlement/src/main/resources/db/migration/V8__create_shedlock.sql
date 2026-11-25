-- settlement V8: ShedLock 잠금 테이블. 정산 대사를 파드 여러 개 중 한 곳에서만 돌린다.
-- 형식은 ShedLock JdbcTemplateLockProvider 기본값 그대로 (market V12와 같음)
create table shedlock (
    name       varchar(64)  not null,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null,
    constraint pk_shedlock primary key (name)
);
