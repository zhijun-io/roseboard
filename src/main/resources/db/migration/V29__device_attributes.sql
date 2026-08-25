create table device_attribute (
    id uuid not null constraint device_attribute_pkey primary key,
    tenant_id uuid not null constraint fk_device_attribute_tenant references tenant(id),
    device_id uuid not null constraint fk_device_attribute_device references device(id) on delete cascade,
    scope varchar(16) not null,
    attribute_key varchar(128) not null,
    value jsonb not null,
    version bigint not null default 1,
    updated_time bigint not null,
    constraint device_attribute_scope_ck check (scope in ('CLIENT', 'SHARED', 'SERVER')),
    constraint device_attribute_key_uq unique (tenant_id, device_id, scope, attribute_key)
);

create index device_attribute_tenant_device_idx
    on device_attribute (tenant_id, device_id, scope);
