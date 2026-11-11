-- market V3: Outbox 테이블 (common-messaging-outbox 권장 SQL 그대로, #277)
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