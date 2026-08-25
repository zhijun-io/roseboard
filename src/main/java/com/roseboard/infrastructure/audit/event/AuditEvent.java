package com.roseboard.infrastructure.audit.event;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.AuditContext;

import java.util.UUID;

/**
 * 审计事件 DTO。落库失败不得影响发布方业务；跨线程发布时应显式携带 {@link AuditContext}。
 */
public record AuditEvent(String action, SecurityUsers principal, AuditTarget target, AuditStatus status,
                         String detailJson, String failureMessage, AuditContext context) {

    public static AuditEvent success(String action, SecurityUsers principal) {
        return success(action, principal, AuditTarget.user(principal));
    }

    public static AuditEvent success(String action, SecurityUsers principal, AuditTarget target) {
        return success(action, principal, target, null, null);
    }

    public static AuditEvent success(String action, SecurityUsers principal, String detailJson) {
        return success(action, principal, AuditTarget.user(principal), detailJson, null);
    }

    public static AuditEvent success(String action, SecurityUsers principal, AuditTarget target,
                                     String detailJson, AuditContext context) {
        return new AuditEvent(action, principal, target, AuditStatus.SUCCEEDED, detailJson, null, context);
    }

    public static AuditEvent failure(String action, SecurityUsers principal, AuditTarget target,
                                     String failureMessage) {
        return failure(action, principal, target, null, failureMessage, null);
    }

    public static AuditEvent failure(String action, SecurityUsers principal, AuditTarget target,
                                     String detailJson, String failureMessage, AuditContext context) {
        return new AuditEvent(action, principal, target, AuditStatus.FAILED, detailJson, failureMessage, context);
    }

    public static AuditEvent loginFailure(String action, String failureMessage) {
        return failure(action, SecurityUsers.anonymous(), AuditTarget.loginAttempt(), failureMessage);
    }

    public static AuditEvent loginFailure(String action, String attemptedName, String failureMessage) {
        return failure(action, SecurityUsers.anonymous(), AuditTarget.loginAttempt(attemptedName), failureMessage);
    }

    public static AuditEvent loginFailure(String action, String attemptedName, String failureMessage,
                                          UUID tenantId, UUID customerId) {
        return failure(action, SecurityUsers.anonymous(),
                AuditTarget.loginAttempt(attemptedName, tenantId, customerId), failureMessage);
    }

    public static AuditEvent anonymousUserSuccess(String action, UUID userId, UUID tenantId, UUID customerId) {
        return success(action, SecurityUsers.anonymous(), AuditTarget.user(userId, tenantId, customerId));
    }
}
