create table telemetry_latest (
    tenant_id uuid not null constraint fk_telemetry_latest_tenant references tenant(id),
    device_id uuid not null constraint fk_telemetry_latest_device references device(id) on delete cascade,
    telemetry_key varchar(128) not null,
    point_value jsonb not null,
    timestamp_ms bigint not null,
    write_id uuid not null,
    updated_time bigint not null,
    constraint telemetry_latest_pkey primary key (tenant_id, device_id, telemetry_key)
);
