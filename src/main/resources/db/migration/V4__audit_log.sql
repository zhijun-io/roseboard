create table if not exists audit_log (
    id uuid not null constraint audit_log_pkey primary key,
    created_time bigint not null,
    actor_user_id uuid,
    tenant_id uuid,
    customer_id uuid,
    action varchar(100) not null,
    entity_type varchar(100),
    entity_id uuid,
    success boolean not null,
    details jsonb
);

create index if not exists audit_log_tenant_time_idx on audit_log (tenant_id, created_time desc);
create index if not exists audit_log_actor_time_idx on audit_log (actor_user_id, created_time desc);
