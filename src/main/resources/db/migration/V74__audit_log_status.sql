-- 将二态 success 收敛为可表达异步操作的三态结果。
alter table audit_log add column if not exists status varchar(20);

update audit_log
set status = case when success then 'SUCCEEDED' else 'FAILED' end
where status is null;

alter table audit_log alter column status set default 'SUCCEEDED';
alter table audit_log alter column status set not null;
alter table audit_log drop column if exists success;
alter table audit_log add constraint audit_log_status_chk
    check (status in ('ACCEPTED', 'SUCCEEDED', 'FAILED'));
