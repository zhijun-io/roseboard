alter table audit_log add column if not exists entity_name varchar(255);
alter table audit_log add column if not exists user_id uuid;
alter table audit_log add column if not exists user_name varchar(320);
alter table audit_log add column if not exists action_type varchar(100);
alter table audit_log add column if not exists action_status varchar(50);
alter table audit_log add column if not exists action_data jsonb;
alter table audit_log add column if not exists action_failure_details text;
