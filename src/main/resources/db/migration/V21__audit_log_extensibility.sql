alter table audit_log add column if not exists actor_type varchar(64);
alter table audit_log add column if not exists metadata jsonb;
alter table audit_log add column if not exists duration_ms bigint;

create index if not exists audit_log_action_time_idx on audit_log (action, created_time desc);