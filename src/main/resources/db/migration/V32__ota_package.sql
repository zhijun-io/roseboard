-- OTA package metadata (artifact bytes added in later slices / same migration for draft support)
create table ota_package (
    id uuid not null constraint ota_package_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null constraint fk_ota_package_tenant references tenant(id),
    device_profile_id uuid constraint fk_ota_package_profile references device_profile(id),
    kind varchar(64) not null,
    title varchar(255) not null,
    version varchar(64) not null,
    tag varchar(255),
    file_name varchar(255),
    content_type varchar(255),
    artifact_source varchar(32) not null,
    external_url varchar(2048),
    size_bytes bigint,
    checksum_algorithm varchar(32),
    checksum_value varchar(128),
    signature_algorithm varchar(64),
    signature_value varchar(512),
    signature_signer varchar(255),
    status varchar(32) not null,
    record_version bigint not null default 1
);

create unique index ota_package_tenant_kind_title_version_uq
    on ota_package (tenant_id, kind, title, version);

create index ota_package_tenant_idx on ota_package (tenant_id);
create index ota_package_profile_kind_idx on ota_package (tenant_id, device_profile_id, kind);

create table ota_package_artifact_chunk (
    package_id uuid not null constraint fk_ota_artifact_package references ota_package(id) on delete cascade,
    chunk_index int not null,
    data bytea not null,
    constraint ota_package_artifact_chunk_pkey primary key (package_id, chunk_index)
);
