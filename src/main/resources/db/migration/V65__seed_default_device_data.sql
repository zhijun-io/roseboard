-- Development bootstrap data for exercising device and WebSocket APIs.
-- The API key value is intentionally deterministic for local testing only:
--   roseboard-default-api-key

insert into device_profile (
    id, created_time, tenant_id, name, description, is_default, additional_info, version, profile_data
)
values (
    '00000000-0000-0000-0000-000000000110',
    extract(epoch from clock_timestamp()) * 1000,
    '00000000-0000-0000-0000-000000000101',
    'default',
    'Roseboard default device profile',
    true,
    '{"source":"bootstrap"}',
    1,
    '{}'
)
on conflict (tenant_id, name) do nothing;

insert into device (
    id, created_time, tenant_id, customer_id, device_profile_id, name, type, label, additional_info, version
)
select
    '00000000-0000-0000-0000-000000000111',
    extract(epoch from clock_timestamp()) * 1000,
    '00000000-0000-0000-0000-000000000101',
    null,
    profile.id,
    'Default Device',
    'default',
    'Default development device',
    '{"source":"bootstrap"}',
    1
from device_profile profile
where profile.tenant_id = '00000000-0000-0000-0000-000000000101'
  and profile.name = 'default'
on conflict (id) do update
set tenant_id = excluded.tenant_id,
    customer_id = excluded.customer_id,
    device_profile_id = excluded.device_profile_id,
    name = excluded.name,
    type = excluded.type,
    label = excluded.label,
    additional_info = excluded.additional_info,
    version = excluded.version;

insert into device_credentials (
    id, created_time, device_id, credentials_type, credentials_id,
    credentials_value, enabled, last_updated_time, version
)
values (
    '00000000-0000-0000-0000-000000000112',
    extract(epoch from clock_timestamp()) * 1000,
    '00000000-0000-0000-0000-000000000111',
    'ACCESS_TOKEN',
    'roseboard-default-device-token',
    null,
    true,
    extract(epoch from clock_timestamp()) * 1000,
    1
)
on conflict (device_id) do update
set credentials_type = excluded.credentials_type,
    credentials_id = excluded.credentials_id,
    credentials_value = excluded.credentials_value,
    enabled = excluded.enabled,
    last_updated_time = excluded.last_updated_time,
    version = excluded.version;

insert into api_key (id, created_time, user_id, key_hash, enabled, description)
values (
    '00000000-0000-0000-0000-000000000113',
    extract(epoch from clock_timestamp()) * 1000,
    '00000000-0000-0000-0000-000000000106',
    'c77f9f2894cbbdce440ed07c50e7acbde7915a4e922284fea3fe595977057715',
    true,
    'Default development API key'
)
on conflict (id) do update
set user_id = excluded.user_id,
    key_hash = excluded.key_hash,
    enabled = excluded.enabled,
    description = excluded.description;
