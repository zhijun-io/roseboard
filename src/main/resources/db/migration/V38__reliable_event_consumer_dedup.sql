-- Consumer dedup for Reliable Event Foundation
create table consumer_dedup (
    id uuid not null constraint consumer_dedup_pkey primary key,
    consumer_scope varchar(512) not null,
    event_id uuid not null,
    status varchar(32) not null,
    aggregate_type varchar(128),
    aggregate_id uuid,
    aggregate_version bigint,
    created_at bigint not null,
    completed_at bigint,
    retain_until bigint,
    constraint consumer_dedup_scope_event_uq unique (consumer_scope, event_id)
);

create index consumer_dedup_status_retain_idx on consumer_dedup (status, retain_until);

alter table outbox_event add column if not exists retain_until bigint;
