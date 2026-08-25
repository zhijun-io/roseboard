-- actor_type 列无任何消费方（查询/过滤均未使用），且"系统行为"可由 actor_user_id IS NULL 表达，
-- 属 V21 引入的投机字段，移除（契约收敛）。
alter table audit_log drop column if exists actor_type;