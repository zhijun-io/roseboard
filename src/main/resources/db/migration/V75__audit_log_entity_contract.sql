-- 审计实体契约收敛：追加记录、稳定快照和独立请求关联字段。
alter table audit_log rename column user_id to actor_user_id;
alter table audit_log rename column user_name to actor_name;

alter table audit_log add column request_id varchar(128);

update audit_log
set request_id = left(context ->> 'requestId', 128)
where request_id is null
  and context ? 'requestId';

update audit_log
set context = context - 'requestId' - 'requestBody' - 'requestParameters'
where context is not null;

update audit_log
set failure_detail = failure_detail - 'stack'
where failure_detail is not null;

alter table audit_log
    alter column created_time type timestamptz
    using to_timestamp(created_time / 1000.0);

create index if not exists audit_log_request_time_idx
    on audit_log (request_id, created_time desc);
