create table if not exists notification_channel_config (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid,
    channel_kind varchar(16) not null,
    enabled boolean not null default true,
    connection_mode varchar(16) not null default 'INHERIT',
    config jsonb not null default '{}'::jsonb,
    secrets jsonb not null default '{}'::jsonb,
    created_time bigint not null,
    updated_time bigint not null,
    constraint fk_notification_channel_config_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade,
    constraint notification_channel_config_mode_chk
        check (connection_mode in ('INHERIT', 'CUSTOM'))
);

create unique index if not exists notification_channel_config_scope_kind_uidx
    on notification_channel_config (tenant_id, channel_kind) nulls not distinct;

insert into notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
select null, 'EMAIL', coalesce((s.json_value::jsonb ->> 'enabled')::boolean, true), 'CUSTOM',
  jsonb_strip_nulls(jsonb_build_object(
    'smtpHost', s.json_value::jsonb ->> 'smtpHost',
    'smtpPort', nullif(s.json_value::jsonb ->> 'smtpPort', '')::int,
    'from', coalesce(s.json_value::jsonb ->> 'from', s.json_value::jsonb ->> 'mailFrom'),
    'username', s.json_value::jsonb ->> 'username',
    'tls', coalesce((s.json_value::jsonb ->> 'enableTls')::boolean, (s.json_value::jsonb ->> 'tls')::boolean, true),
    'oauth2', coalesce(
      s.json_value::jsonb -> 'oauth2',
      jsonb_strip_nulls(jsonb_build_object(
        'clientId', s.json_value::jsonb ->> 'clientId',
        'clientSecret', s.json_value::jsonb ->> 'clientSecret',
        'authUri', s.json_value::jsonb ->> 'authUri',
        'redirectUri', s.json_value::jsonb ->> 'redirectUri',
        'tokenUri', s.json_value::jsonb ->> 'tokenUri',
        'scope', s.json_value::jsonb -> 'scope'
      ))
    ))),
  jsonb_strip_nulls(jsonb_build_object(
    'password', s.json_value::jsonb ->> 'password',
    'refreshToken', coalesce(
      s.json_value::jsonb -> 'oauth2' ->> 'refreshToken',
      s.json_value::jsonb ->> 'refreshToken'),
    'clientSecret', s.json_value::jsonb ->> 'clientSecret')),
  s.created_time, s.created_time
from admin_settings s
where s.key = 'mail' and s.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
  and not exists (select 1 from notification_channel_config c where c.tenant_id is null and c.channel_kind = 'EMAIL');

insert into notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
select null, 'SMS', coalesce((s.json_value::jsonb ->> 'enabled')::boolean, false), 'CUSTOM',
  jsonb_strip_nulls(jsonb_build_object(
    'provider', s.json_value::jsonb ->> 'provider',
    'baseUrl', s.json_value::jsonb ->> 'baseUrl')),
  jsonb_strip_nulls(jsonb_build_object('apiKey', s.json_value::jsonb ->> 'apiKey')),
  s.created_time, s.created_time
from admin_settings s
where s.key in ('sms', 'smsSettings') and s.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
  and not exists (select 1 from notification_channel_config c where c.tenant_id is null and c.channel_kind = 'SMS');

insert into notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
select null, 'WEB', true, 'INHERIT', '{}'::jsonb, '{}'::jsonb,
  (extract(epoch from clock_timestamp()) * 1000)::bigint,
  (extract(epoch from clock_timestamp()) * 1000)::bigint
where not exists (select 1 from notification_channel_config c where c.tenant_id is null and c.channel_kind = 'WEB');

delete from admin_settings
where key in ('mail', 'sms', 'smsSettings')
  and tenant_id = '00000000-0000-0000-0000-000000000000'::uuid;
