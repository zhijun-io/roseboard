insert into admin_settings (id, tenant_id, created_time, key, json_value)
select '00000000-0000-0000-0000-000000000110'::uuid,
       '00000000-0000-0000-0000-000000000000'::uuid,
       extract(epoch from clock_timestamp()) * 1000,
       'jwt',
       '{"issuer":"roseboard","tokenSigningKey":"cm9zZWJvYXJkLWRldmVsb3BtZW50LXNlY3JldC1rZXktMzJieXRlcw==","accessTokenTtl":"PT15M","refreshTokenTtl":"P30D"}'
where not exists (
    select 1 from admin_settings
    where tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
      and key = 'jwt'
);
