package com.roseboard.infrastructure.audit;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;

/**
 * 审计入口：构造并持久化 {@link AuditRecord}。落库失败由实现隔离，不传播给调用方。
 */
public interface AuditTemplate {

    void record(String action, SecurityUsers principal, AuditTarget target, AuditStatus status,
                AuditContext context, String detailJson, String failureMessage, Long durationMs);

    default void record(AuditEvent event, AuditContextProvider contextProvider) {
        AuditContext context = event.context() != null ? event.context() : contextProvider.current();
        record(event.action(), event.principal(), event.target(), event.status(),
                context, event.detailJson(), event.failureMessage(), null);
    }
}
