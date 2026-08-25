package com.roseboard.infrastructure.audit.context;

import com.roseboard.infrastructure.audit.AuditContext;

/**
 * 优先 ThreadLocal（{@link AuditContextHolder}），再委托 Servlet 等环境 Provider。
 */
public class ChainedAuditContextProvider implements AuditContextProvider {

    private final AuditContextProvider delegate;

    public ChainedAuditContextProvider(AuditContextProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AuditContext current() {
        AuditContext held = AuditContextHolder.current();
        if (held != null && !held.isEmpty()) {
            return held;
        }
        return delegate.current();
    }
}
