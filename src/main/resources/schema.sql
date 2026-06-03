create table if not exists notification_task (
    id uuid primary key,
    source_system varchar(128) not null,
    target_url text not null,
    method varchar(16) not null,
    headers_json text not null,
    body text not null,
    request_fingerprint varchar(64) not null,
    idempotency_key varchar(256) not null,
    status varchar(32) not null,
    attempt_count integer not null,
    max_attempts integer not null,
    next_attempt_at timestamp null,
    locked_until timestamp null,
    version bigint not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint uk_notification_idempotency unique (source_system, idempotency_key)
);

create index if not exists idx_notification_due
    on notification_task (status, next_attempt_at, locked_until);

create table if not exists delivery_attempt (
    id uuid primary key,
    notification_id uuid not null,
    attempt_no integer not null,
    status varchar(32) not null,
    response_status integer null,
    error_type varchar(64) null,
    error_detail varchar(1024) null,
    latency_ms bigint not null,
    created_at timestamp not null,
    constraint fk_delivery_attempt_notification
        foreign key (notification_id) references notification_task (id)
);

create index if not exists idx_delivery_attempt_notification
    on delivery_attempt (notification_id, created_at);
