package com.roseboard.infrastructure.audit.context;

import com.roseboard.infrastructure.audit.AuditContext;

/**
 * 非 Servlet 线程（MQTT、定时任务等）显式携带 {@link AuditContext} 的 ThreadLocal 持有者。
 */
public final class AuditContextHolder {

    private static final ThreadLocal<AuditContext> CURRENT = new ThreadLocal<>();

    private AuditContextHolder() {
    }

    public static AuditContext current() {
        return CURRENT.get();
    }

    public static void run(AuditContext context, Runnable action) {
        AuditContext previous = CURRENT.get();
        try {
            if (context == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(context);
            }
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
