-- Keep the three development accounts compatible with ThingsBoard install defaults.
update tb_user set email = 'sysadmin@thingsboard.org', first_name = 'System', last_name = 'Administrator', authority = 'SYS_ADMIN'
where id = '00000000-0000-0000-0000-000000000103';
update user_credentials set password = '$2a$10$EEhM/5GBEJVwl3FEKUpWzexvO/XM0O8AhLXHI/aaWI7QoSYOQrCQS'
where user_id = '00000000-0000-0000-0000-000000000103';

update tb_user set email = 'tenant@thingsboard.org', first_name = 'Tenant', last_name = 'Administrator', authority = 'TENANT_ADMIN'
where id = '00000000-0000-0000-0000-000000000106';
update user_credentials set password = '$2a$10$.4DtmelBKolhdSEgbouJkeLgCmOJ96.a/x3QUDepwdk9nYwfo2jaC'
where user_id = '00000000-0000-0000-0000-000000000106';

insert into tb_user (id, created_time, tenant_id, customer_id, email, first_name, last_name, authority, version)
values ('00000000-0000-0000-0000-000000000108', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000102',
        'customer@thingsboard.org', 'Customer', 'User', 'CUSTOMER_USER', 1)
on conflict (id) do update set email = excluded.email, customer_id = excluded.customer_id, authority = excluded.authority;
insert into user_credentials (id, created_time, user_id, password, enabled, additional_info, failed_login_attempts)
values ('00000000-0000-0000-0000-000000000109', extract(epoch from clock_timestamp()) * 1000,
        '00000000-0000-0000-0000-000000000108',
        '$2a$10$q245P3mPVOUb1p4lWQgzc.cc56xnJOpQOTo1597pT3s2OMWnO1Cwq', true, '{}', 0)
on conflict (id) do update set password = excluded.password, enabled = true;
