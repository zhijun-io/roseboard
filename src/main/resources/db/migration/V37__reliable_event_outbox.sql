-- Outbox + test aggregate for Reliable Event Foundation
create table outbox_event (
    id uuid not null constraint outbox_event_pkey primary key,
    tenant_id uuid not null,
    aggregate_type varchar(128) not null,
    aggregate_id uuid not null,
    event_type varchar(255) not null,
    schema_id varchar(255) not null,
    schema_version int not null,
    content_type varchar(64) not null,
    aggregate_version bigint not null,
    payload bytea not null,
    publish_status varchar(32) not null,
    route_key varchar(512) not null,
    route_topic varchar(255),
    route_partition int,
    created_at bigint not null,
    claim_owner varchar(255),
    claim_generation bigint,
    lease_until bigint,
    attempt_count int not null default 0,
    published_at bigint,
    last_error varchar
);

create index outbox_event_publish_idx on outbox_event (publish_status, created_at);
create index outbox_event_aggregate_idx on outbox_event (tenant_id, aggregate_type, aggregate_id, aggregate_version);
