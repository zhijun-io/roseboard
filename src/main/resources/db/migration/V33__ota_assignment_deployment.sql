-- OTA assignment and deployment (Spec 4)
create table ota_assignment (
    id uuid not null constraint ota_assignment_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null constraint fk_ota_assignment_tenant references tenant(id),
    device_id uuid constraint fk_ota_assignment_device references device(id),
    device_profile_id uuid constraint fk_ota_assignment_profile references device_profile(id),
    kind varchar(64) not null,
    package_id uuid not null constraint fk_ota_assignment_package references ota_package(id),
    source varchar(16) not null,
    assignment_version bigint not null default 1,
    request_key varchar(128),
    constraint ota_assignment_target_chk check (
        (device_id is not null and device_profile_id is null)
        or (device_id is null and device_profile_id is not null)
    )
);

create unique index ota_assignment_device_kind_uq
    on ota_assignment (tenant_id, device_id, kind) where device_id is not null;

create unique index ota_assignment_profile_kind_uq
    on ota_assignment (tenant_id, device_profile_id, kind) where device_profile_id is not null;

create unique index ota_assignment_request_key_uq
    on ota_assignment (tenant_id, kind, request_key)
    where request_key is not null;

create index ota_assignment_package_idx on ota_assignment (package_id);

create table ota_deployment (
    id uuid not null constraint ota_deployment_pkey primary key,
    created_time bigint not null,
    updated_time bigint not null,
    tenant_id uuid not null constraint fk_ota_deployment_tenant references tenant(id),
    device_id uuid not null constraint fk_ota_deployment_device references device(id),
    package_id uuid not null constraint fk_ota_deployment_package references ota_package(id),
    kind varchar(64) not null,
    assignment_id uuid constraint fk_ota_deployment_assignment references ota_assignment(id),
    assignment_version bigint not null,
    state varchar(32) not null,
    state_version bigint not null default 1,
    deadline bigint,
    failure_code varchar(64),
    digest_verified boolean not null default false,
    request_key varchar(128)
);

create index ota_deployment_device_kind_idx on ota_deployment (tenant_id, device_id, kind);
create index ota_deployment_package_idx on ota_deployment (package_id);
create index ota_deployment_state_idx on ota_deployment (state, updated_time);

create unique index ota_deployment_active_device_kind_uq
    on ota_deployment (tenant_id, device_id, kind)
    where state not in ('UPDATED', 'FAILED', 'CANCELLED');
