-- Reliable Event Foundation: Transport Inbox + Command Inbox
create table transport_inbox (
    id uuid not null constraint transport_inbox_pkey primary key,
    tenant_id uuid not null,
    device_id uuid not null,
    message_type varchar(255) not null,
    message_id varchar(255) not null,
    request_hash varchar(64) not null,
    status varchar(32) not null,
    received_at bigint not null,
    completed_at bigint,
    result_summary varchar,
    error_class varchar(64),
    retain_until bigint,
    processing_deadline bigint,
    constraint transport_inbox_scope_uq unique (tenant_id, device_id, message_type, message_id)
);

create table command_inbox (
    id uuid not null constraint command_inbox_pkey primary key,
    principal_scope varchar(512) not null,
    command_type varchar(255) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(64) not null,
    status varchar(32) not null,
    received_at bigint not null,
    completed_at bigint,
    result_summary varchar,
    error_class varchar(64),
    retain_until bigint,
    processing_deadline bigint,
    constraint command_inbox_scope_uq unique (principal_scope, command_type, idempotency_key)
);

create index transport_inbox_status_retain_idx on transport_inbox (status, retain_until);
create index command_inbox_status_retain_idx on command_inbox (status, retain_until);
create index transport_inbox_processing_deadline_idx on transport_inbox (status, processing_deadline);
create index command_inbox_processing_deadline_idx on command_inbox (status, processing_deadline);
