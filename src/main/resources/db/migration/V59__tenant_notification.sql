-- Minimal in-app notifications for usage threshold alerts (not full TB notification center).
create table if not exists tenant_notification (
    id uuid primary key,
    created_time bigint not null,
    tenant_id uuid not null,
    type varchar(64) not null,
    title varchar(255) not null,
    body text not null,
    read_flag boolean not null default false,
    constraint fk_tenant_notification_tenant foreign key (tenant_id) references tenant(id) on delete cascade
);

create index if not exists tenant_notification_tenant_created_idx
    on tenant_notification (tenant_id, created_time desc);
