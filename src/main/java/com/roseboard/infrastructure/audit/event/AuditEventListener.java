package com.roseboard.infrastructure.audit.event;

import com.roseboard.infrastructure.audit.AuditTemplate;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 审计事件监听（框架）：接收使用方发布的 {@link AuditEvent}，交给 {@link AuditTemplate} 落库。
 * 事务内发布时在提交后写入；无事务时立即写入。
 */
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditTemplate template;
    private final AuditContextProvider contextProvider;

    public AuditEventListener(AuditTemplate template, AuditContextProvider contextProvider) {
        this.template = template;
        this.contextProvider = contextProvider;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEvent(AuditEvent event) {
        try {
            template.record(event, contextProvider);
        } catch (RuntimeException failure) {
            log.warn("audit event handling failed and was isolated from business flow: action={}",
                    event.action(), failure);
        }
    }
}
