create table if not exists device_telemetry (
    id uuid not null constraint device_telemetry_pkey primary key,
    message_id uuid not null constraint device_telemetry_message_unq_key unique,
    device_id uuid not null constraint fk_device_telemetry_device references device(id) on delete cascade,
    ts bigint not null,
    payload jsonb not null,
    created_time bigint not null
);

create index if not exists device_telemetry_device_ts_idx
    on device_telemetry(device_id, ts desc);
