-- 加速按 occurred_at 的全局保留清理扫描。
create index if not exists audit_log_occurred_at_idx on audit_log (occurred_at asc);
