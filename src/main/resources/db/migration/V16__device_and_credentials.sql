create table if not exists device (
    id uuid not null constraint device_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null,
    customer_id uuid,
    name varchar(255) not null,
    type varchar(255),
    label varchar(255),
    additional_info jsonb,
    version bigint default 1,
    constraint device_tenant_name_unq_key unique (tenant_id, name),
    constraint fk_device_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_device_customer foreign key (customer_id) references customer(id)
);

create index if not exists device_tenant_idx on device(tenant_id);
create index if not exists device_customer_idx on device(customer_id);

create table if not exists device_credentials (
    id uuid not null constraint device_credentials_pkey primary key,
    created_time bigint not null,
    device_id uuid not null constraint device_credentials_device_unq_key unique,
    credentials_type varchar(64) not null,
    credentials_id varchar(255),
    credentials_value_hash varchar(255) not null,
    enabled boolean not null default true,
    last_updated_time bigint not null,
    constraint fk_device_credentials_device foreign key (device_id) references device(id) on delete cascade,
    constraint device_credentials_type_hash_unq_key unique (credentials_type, credentials_value_hash)
);

create index if not exists device_credentials_hash_idx
    on device_credentials(credentials_type, credentials_value_hash, enabled);
