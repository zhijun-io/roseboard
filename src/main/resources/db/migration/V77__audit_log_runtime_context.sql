-- 将 HTTP 专属关联字段收敛为可跨运行环境使用的审计上下文。
alter table audit_log rename column created_time to occurred_at;
alter table audit_log rename column request_id to correlation_id;

alter table audit_log add column if not exists origin varchar(32);

-- 历史记录没有可靠的来源信息，不能伪造为 HTTP 或 INTERNAL。
update audit_log set origin = 'UNKNOWN' where origin is null;
alter table audit_log alter column origin set default 'UNKNOWN';
alter table audit_log alter column origin set not null;
alter table audit_log add constraint audit_log_origin_chk
    check (origin in ('UNKNOWN', 'HTTP', 'MQTT', 'MESSAGE', 'SCHEDULED', 'INTERNAL'));

alter index if exists audit_log_request_time_idx rename to audit_log_correlation_time_idx;
