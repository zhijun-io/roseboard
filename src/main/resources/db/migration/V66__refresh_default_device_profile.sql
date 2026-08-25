-- Keep the tenant's existing default profile aligned with the bootstrap device data.
update device_profile
set description = 'Roseboard default device profile',
    is_default = true,
    additional_info = '{"source":"bootstrap"}',
    profile_data = '{}',
    version = 1
where tenant_id = '00000000-0000-0000-0000-000000000101'
  and name = 'default';
