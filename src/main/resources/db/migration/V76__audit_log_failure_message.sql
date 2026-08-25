-- 失败结果只保留面向使用者的消息，不持久化异常类型或堆栈。
alter table audit_log add column failure_message varchar(2048);

update audit_log
set failure_message = left(failure_detail ->> 'message', 2048)
where failure_detail is not null;

alter table audit_log drop column failure_detail;
