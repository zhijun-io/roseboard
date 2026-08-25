-- ThingsBoard-compatible queue_stats identity dimensions (baseline schema-entities.sql).
-- Local reversible deviation: V35 instead of plan V19 because workspace already applied V20+.
create table queue_stats (
    id uuid not null constraint queue_stats_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null,
    queue_name varchar(255) not null,
    service_id varchar(255) not null,
    constraint queue_stats_name_unq_key unique (tenant_id, queue_name, service_id)
);
