create table telemetry_point (
    id uuid not null constraint telemetry_point_pkey primary key,
    tenant_id uuid not null constraint fk_telemetry_point_tenant references tenant(id),
    device_id uuid not null constraint fk_telemetry_point_device references device(id) on delete cascade,
    telemetry_key varchar(128) not null,
    point_value jsonb not null,
    timestamp_ms bigint not null,
    write_id uuid not null,
    created_time bigint not null,
    constraint telemetry_point_write_uq unique (tenant_id, device_id, write_id)
);

create index telemetry_point_history_idx
    on telemetry_point (tenant_id, device_id, telemetry_key, timestamp_ms desc);
