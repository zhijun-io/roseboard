-- 审计表契约收敛（第一性原理）：
-- 移除 V14 时代 ThingsBoard 兼容镜像列：user_id(镜像 actor_user_id)、
-- action_type(镜像 action)、action_status(派生自 success)、action_data(镜像 detail)。
-- 列名单数化与语义化：user_name -> actor_name（执行者名称快照）、
-- action_failure_details -> failure_details、details -> detail、metadata -> context。
alter table audit_log drop column if exists user_id;
alter table audit_log drop column if exists action_type;
alter table audit_log drop column if exists action_status;
alter table audit_log drop column if exists action_data;
alter table audit_log rename column user_name to actor_name;
alter table audit_log rename column action_failure_details to failure_details;
alter table audit_log rename column details to detail;
alter table audit_log rename column metadata to context;