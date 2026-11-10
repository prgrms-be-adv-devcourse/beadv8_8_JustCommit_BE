-- Outbox 테이블 권장 SQL (common-messaging-outbox).
-- 이벤트를 보내는 서비스는 이 내용을 자기 Flyway 새 버전으로 복사한다 (예: V2__create_outbox_event.sql).
-- 이미 적용된 V1은 고치지 않는다.
create table outbox_event (
    id            uuid                        not null,
    aggregate_id  varchar(100)                not null,
    topic         varchar(100)                not null,
    event_type    varchar(100)                not null,
    payload       text                        not null,
    traceparent   varchar(55),
    status        varchar(20)                 not null,
    published_at  timestamp(6) with time zone,
    attempt_count integer                     not null default 0,
    last_error    varchar(500),
    created_at    timestamp(6) with time zone not null,
    constraint pk_outbox_event primary key (id)
);

-- 재전송 후보 조회 (status = 'PENDING' 오래된 순)와 정리용
create index idx_outbox_event_status_created_at on outbox_event (status, created_at);