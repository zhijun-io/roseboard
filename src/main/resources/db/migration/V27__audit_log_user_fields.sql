-- 统一审计主体字段命名，与 Java AuditLogEntity 的 userId/userName 保持一致。
alter table audit_log rename column actor_user_id to user_id;
alter table audit_log rename column actor_name to user_name;
