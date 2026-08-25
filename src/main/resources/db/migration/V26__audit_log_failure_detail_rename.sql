-- 列名单数化：failure_details -> failure_detail（与实体 failureDetail 字段一致）。
alter table audit_log rename column failure_details to failure_detail;