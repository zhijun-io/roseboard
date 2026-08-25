-- failure_details 从纯文本升级为 jsonb：结构化失败（type/message/stack）。
-- 旧文本数据无损转为 {message: <原文>}。
alter table audit_log alter column failure_details type jsonb using
    case when failure_details is null then null
         else jsonb_build_object('message', failure_details) end;