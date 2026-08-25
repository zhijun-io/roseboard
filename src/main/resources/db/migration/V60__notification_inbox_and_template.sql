create table if not exists notification_template (
    id uuid not null constraint notification_template_pkey primary key,
    created_time bigint not null,
    updated_time bigint not null,
    tenant_id uuid,
    template_key varchar(128) not null,
    render_engine varchar(32) not null default 'SIMPLE',
    notification_type varchar(64) not null,
    name varchar(255) not null,
    delivery_methods jsonb not null,
    constraint fk_notification_template_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade
);

create unique index if not exists notification_template_tenant_key_uidx
    on notification_template (tenant_id, template_key) nulls not distinct;

create table if not exists notification (
    id uuid not null constraint notification_pkey primary key,
    created_time bigint not null,
    scope_id uuid not null,
    recipient_id uuid not null,
    type varchar(64) not null,
    subject varchar(512) not null,
    body text not null,
    status varchar(16) not null default 'UNREAD',
    read_time bigint,
    constraint fk_notification_scope foreign key (scope_id) references tenant(id) on delete cascade,
    constraint fk_notification_recipient foreign key (recipient_id) references tb_user(id) on delete cascade,
    constraint notification_status_chk check (status in ('UNREAD', 'READ'))
);

create index if not exists notification_recipient_created_idx
    on notification (recipient_id, created_time desc);

create index if not exists notification_recipient_status_idx
    on notification (recipient_id, status)
    where status = 'UNREAD';

insert into notification_template (
    id, tenant_id, template_key, render_engine, notification_type, name,
    delivery_methods, created_time, updated_time
) values
(
    gen_random_uuid(), null, 'auth.activation', 'FREEMARKER', 'AUTH', 'Account activation',
    '{"EMAIL":{"enabled":true,"subject":"Activate your Roseboard account","body":"<!doctype html><html><body><p>Activate your Roseboard account:</p><p><a href=\"$${link}\">$${link}</a></p></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.password-reset', 'FREEMARKER', 'AUTH', 'Password reset',
    '{"EMAIL":{"enabled":true,"subject":"Reset your Roseboard password","body":"<!doctype html><html><body><p>Reset your Roseboard password:</p><p><a href=\"$${link}\">$${link}</a></p></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.password-was-reset', 'FREEMARKER', 'AUTH', 'Password was reset',
    '{"EMAIL":{"enabled":true,"subject":"Your Roseboard password was reset","body":"<!doctype html><html><body><p>Your Roseboard password was reset successfully.</p><p><a href=\"$${link}\">Sign in</a></p></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.account-activated', 'FREEMARKER', 'AUTH', 'Account activated',
    '{"EMAIL":{"enabled":true,"subject":"Your Roseboard account is active","body":"<!doctype html><html><body><p>Your Roseboard account is now active.</p><p><a href=\"$${link}\">Sign in</a></p></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.account-lockout', 'FREEMARKER', 'AUTH', 'Account lockout',
    '{"EMAIL":{"enabled":true,"subject":"Your Roseboard account is locked","body":"<!doctype html><html><body><p>Your Roseboard account has been locked.</p><p>Contact $${lockoutEmail} to restore access.</p><#if maxFailedLoginAttempts?has_content><p>Maximum failed login attempts: $${maxFailedLoginAttempts}</p></#if></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.mfa-email', 'FREEMARKER', 'AUTH', 'MFA email code',
    '{"EMAIL":{"enabled":true,"subject":"Your Roseboard verification code","body":"<!doctype html><html><body><p>Your Roseboard verification code is <strong>$${code}</strong>.</p><p>This code expires in 5 minutes.</p></body></html>","html":true}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'auth.mfa-sms', 'SIMPLE', 'AUTH', 'MFA SMS code',
    '{"SMS":{"enabled":true,"body":"Code: $${code}"}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
),
(
    gen_random_uuid(), null, 'usage.threshold', 'SIMPLE', 'USAGE_LIMIT', 'Usage threshold',
    '{"WEB":{"enabled":true,"subject":"Usage $${status}","body":"$${metricKey} is $${status} for period $${period}."},
      "EMAIL":{"enabled":true,"subject":"Usage $${status}","body":"$${metricKey} is $${status} for period $${period}.","html":false}}'::jsonb,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
);

drop table if exists tenant_notification;
