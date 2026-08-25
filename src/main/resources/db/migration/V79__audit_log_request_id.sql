-- 关联 ID 列名改回 request_id，与 HTTP X-Request-Id 及查询 API 对齐。
alter table audit_log rename column correlation_id to request_id;

alter index if exists audit_log_correlation_time_idx rename to audit_log_request_time_idx;
