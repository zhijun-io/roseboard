-- ThingsBoard-compatible queue configuration table (baseline schema-entities.sql).
-- Local reversible deviation: V34 instead of plan V18 because workspace already applied V20+.
create table queue (
    id uuid not null constraint queue_pkey primary key,
    created_time bigint not null,
    tenant_id uuid,
    name varchar(255),
    topic varchar(255),
    poll_interval int,
    partitions int,
    consumer_per_partition boolean,
    pack_processing_timeout bigint,
    submit_strategy varchar(255),
    processing_strategy varchar(255),
    additional_info varchar
);
