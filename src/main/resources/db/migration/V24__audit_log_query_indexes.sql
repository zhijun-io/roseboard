-- 审计查询路径补索引：/logs/customer/{customerId} 按 (customer_id, created_time) 过滤，
-- /logs/entity/{entityType}/{entityId} 按 (entity_type, entity_id, created_time) 过滤。
create index if not exists audit_log_customer_time_idx on audit_log (customer_id, created_time desc);
create index if not exists audit_log_entity_time_idx on audit_log (entity_type, entity_id, created_time desc);