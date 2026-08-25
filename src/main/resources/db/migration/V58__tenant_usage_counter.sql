-- Monthly tenant usage counters (TB ApiUsage-inspired, minimal).
create table if not exists tenant_usage_counter (
    tenant_id uuid not null,
    period varchar(7) not null,
    metric_key varchar(64) not null,
    used_value bigint not null default 0,
    constraint tenant_usage_counter_pkey primary key (tenant_id, period, metric_key),
    constraint fk_tenant_usage_tenant foreign key (tenant_id) references tenant(id) on delete cascade
);

create index if not exists tenant_usage_counter_tenant_period_idx
    on tenant_usage_counter (tenant_id, period);
