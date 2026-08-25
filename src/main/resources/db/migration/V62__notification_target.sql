create table if not exists notification_target (
    id uuid not null constraint notification_target_pkey primary key,
    created_time bigint not null,
    updated_time bigint not null,
    tenant_id uuid not null,
    name varchar(255) not null,
    target_type varchar(32) not null,
    user_ids jsonb not null default '[]'::jsonb,
    constraint fk_notification_target_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade,
    constraint notification_target_type_chk
        check (target_type in ('TENANT_ADMINS', 'USER_IDS'))
);

create index if not exists notification_target_tenant_idx
    on notification_target (tenant_id, name);
