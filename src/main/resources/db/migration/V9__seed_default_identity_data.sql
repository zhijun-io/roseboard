-- Development bootstrap data. Replace the default passwords immediately in non-development environments.
insert into tenant_profile (id, created_time, name, description, is_default, isolated_tb_core, isolated_tb_rule_engine)
values ('00000000-0000-0000-0000-000000000100', extract(epoch from clock_timestamp()) * 1000,
        'Default tenant profile', 'Roseboard default tenant profile', true, false, false)
on conflict (id) do nothing;

insert into tenant (id, created_time, tenant_profile_id, title, email, version)
values ('00000000-0000-0000-0000-000000000101', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000100', 'Default tenant', 'tenant@roseboard.local', 1)
on conflict (id) do nothing;

insert into customer (id, created_time, tenant_id, title, email, is_public, version)
values ('00000000-0000-0000-0000-000000000102', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000101', 'Default customer', 'customer@roseboard.local', false, 1)
on conflict (id) do nothing;

insert into tb_user (id, created_time, tenant_id, customer_id, email, first_name, last_name, authority, version)
values ('00000000-0000-0000-0000-000000000103', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000101', null, 'admin@roseboard.local', 'Roseboard', 'Administrator', 'SYS_ADMIN', 1)
on conflict (id) do nothing;

insert into user_credentials (id, created_time, user_id, password, enabled, additional_info, failed_login_attempts)
values ('00000000-0000-0000-0000-000000000104', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000103',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', true, '{}', 0)
on conflict (id) do nothing;

insert into admin_settings (id, tenant_id, created_time, key, json_value)
values ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000101',
        extract(epoch from clock_timestamp()) * 1000, 'security',
        '{"passwordPolicy":{"minimumLength":6,"maximumLength":72,"minimumUppercaseLetters":0,"minimumLowercaseLetters":0,"minimumDigits":0,"minimumSpecialCharacters":0,"allowWhitespaces":true}}')
on conflict (id) do nothing;

insert into tb_user (id, created_time, tenant_id, customer_id, email, first_name, last_name, authority, version)
values ('00000000-0000-0000-0000-000000000106', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000101', null, 'tenant-admin@roseboard.local', 'Tenant', 'Administrator', 'TENANT_ADMIN', 1)
on conflict (id) do nothing;

insert into user_credentials (id, created_time, user_id, password, enabled, additional_info, failed_login_attempts)
values ('00000000-0000-0000-0000-000000000107', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000106',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', true, '{}', 0)
on conflict (id) do nothing;
