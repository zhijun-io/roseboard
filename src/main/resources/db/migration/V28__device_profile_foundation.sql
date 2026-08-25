create table device_profile (
    id uuid not null constraint device_profile_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null constraint fk_device_profile_tenant references tenant(id),
    name varchar(255) not null,
    description varchar(1024),
    is_default boolean not null default false,
    additional_info jsonb,
    version bigint not null default 1
);

create unique index device_profile_tenant_name_uq
    on device_profile (tenant_id, name);

create unique index device_profile_one_default_uq
    on device_profile (tenant_id) where is_default;

alter table device add column device_profile_id uuid;

insert into device_profile (id, created_time, tenant_id, name, description, is_default, version)
select gen_random_uuid(), extract(epoch from clock_timestamp()) * 1000,
       t.id, 'default', 'Default device profile', true, 1
from tenant t
where not exists (
    select 1 from device_profile p where p.tenant_id = t.id
);

update device d
set device_profile_id = p.id
from device_profile p
where p.tenant_id = d.tenant_id
  and p.is_default
  and d.device_profile_id is null;

alter table device alter column device_profile_id set not null;
alter table device add constraint fk_device_profile
    foreign key (device_profile_id) references device_profile(id);

create index device_profile_tenant_idx on device_profile (tenant_id);
create index device_profile_device_idx on device (device_profile_id);
