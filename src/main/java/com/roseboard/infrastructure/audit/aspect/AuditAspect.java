package com.roseboard.infrastructure.audit.aspect;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.AuditTemplate;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.common.security.SecurityUserProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * {@link Audited} 切面：执行业务方法并记录成功或失败审计。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final String UNRESOLVED_ACTION = "AUDIT_ACTION_UNRESOLVED";

    private final AuditTemplate auditTemplate;
    private final SecurityUserProvider principalProvider;
    private final AuditExpressionEvaluator expressionEvaluator;
    private final AuditContextProvider contextProvider;

    public AuditAspect(AuditTemplate auditTemplate, SecurityUserProvider principalProvider,
                       AuditExpressionEvaluator expressionEvaluator,
                       AuditContextProvider contextProvider) {
        this.auditTemplate = auditTemplate;
        this.principalProvider = principalProvider;
        this.expressionEvaluator = expressionEvaluator;
        this.contextProvider = contextProvider;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        SecurityUsers principal = principalProvider.currentUser();
        long startedNanos = System.nanoTime();
        String action = resolveActionSafely(audited, joinPoint, principal);
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable businessFailure) {
            recordSafely(joinPoint, audited, action, principal, null, AuditStatus.FAILED,
                    businessFailure.getMessage(), startedNanos);
            throw businessFailure;
        }
        if (result instanceof DeferredResult<?>) {
            return result;
        }
        recordSafely(joinPoint, audited, action, principal, result, AuditStatus.SUCCEEDED, null, startedNanos);
        return result;
    }

    private String resolveActionSafely(Audited audited, ProceedingJoinPoint joinPoint, SecurityUsers principal) {
        try {
            return expressionEvaluator.resolveAction(audited, joinPoint, principal);
        } catch (RuntimeException failure) {
            log.warn("audit action SpEL failed and was isolated from business flow: expression={}",
                    audited.action(), failure);
            return literalOrFallback(audited.action());
        }
    }

    private static String literalOrFallback(String actionExpression) {
        if (actionExpression == null || actionExpression.isBlank()) {
            return UNRESOLVED_ACTION;
        }
        if (actionExpression.indexOf('#') < 0 && actionExpression.indexOf("T(") < 0) {
            return actionExpression;
        }
        return UNRESOLVED_ACTION;
    }

    private void recordSafely(ProceedingJoinPoint joinPoint, Audited audited, String action,
                              SecurityUsers principal, Object result, AuditStatus status,
                              String failureMessage, long startedNanos) {
        try {
            StandardEvaluationContext context = expressionEvaluator.buildContext(joinPoint, result, principal);
            AuditTarget target = expressionEvaluator.resolveTarget(context, audited, principal);
            auditTemplate.record(action, principal, target, status, contextProvider.current(),
                    null, failureMessage, elapsedMs(startedNanos));
        } catch (RuntimeException auditFailure) {
            log.warn("audit recording failed and was isolated from business flow: action={}",
                    action, auditFailure);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
