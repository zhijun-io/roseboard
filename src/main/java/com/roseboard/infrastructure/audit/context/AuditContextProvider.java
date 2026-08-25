package com.roseboard.infrastructure.audit.context;

import com.roseboard.infrastructure.audit.AuditContext;

/** 审计上下文读取接口。核心审计模块不依赖具体传输环境。 */
public interface AuditContextProvider {

    AuditContext current();
}
