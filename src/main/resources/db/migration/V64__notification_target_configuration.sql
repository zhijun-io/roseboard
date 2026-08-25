alter table notification_target
    add column if not exists configuration jsonb not null default '{}'::jsonb;
