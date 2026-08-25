package com.roseboard.setting.security;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Roseboard adapter that maps security lifecycle events to audit events. */
@Component
public class DefaultSecurityEventRecorder implements SecurityEventRecorder {
    private final ApplicationEventPublisher eventPublisher;

    public DefaultSecurityEventRecorder(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void recordLoginSuccess(SecurityUser principal) {
        eventPublisher.publishEvent(AuditEvent.success(AuditActions.LOGIN_SUCCESS, principal));
    }

    @Override
    public void recordLoginFailure(SecurityUser principal, String subject, String detail, String reason) {
        String target = StringUtils.hasText(subject) ? subject : null;
        AuditTarget auditTarget = principal == null
                ? (target == null ? AuditTarget.loginAttempt() : AuditTarget.loginAttempt(target))
                : AuditTarget.loginAttempt(target, principal.getTenantId(), principal.getCustomerId());
        eventPublisher.publishEvent(AuditEvent.failure(
                AuditActions.LOGIN_FAILED,
                SecurityUsers.anonymous(),
                auditTarget,
                detail,
                reason,
                null));
    }

    @Override
    public void recordLogout(SecurityUser principal) {
        eventPublisher.publishEvent(AuditEvent.success(AuditActions.LOGOUT, principal));
    }
}
