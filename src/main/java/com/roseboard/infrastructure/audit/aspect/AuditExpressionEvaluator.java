package com.roseboard.infrastructure.audit.aspect;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责审计注解中的 SpEL、方法参数和目标实体解析。
 */
public class AuditExpressionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();
    private final ConcurrentHashMap<String, Expression> expressions = new ConcurrentHashMap<>();

    public String resolveAction(Audited audited, ProceedingJoinPoint joinPoint, SecurityUsers principal) {
        String action = audited.action();
        if (isLiteral(action)) {
            return action;
        }
        Object value = evaluate(buildContext(joinPoint, null, principal), action);
        if (value == null) {
            throw new IllegalStateException("@Audited action SpEL 求值为 null: " + action);
        }
        return value.toString();
    }

    public StandardEvaluationContext buildContext(ProceedingJoinPoint joinPoint, Object result,
                                                  SecurityUsers principal) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("result", result);
        context.setVariable("principal", principal);
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(
                ((MethodSignature) joinPoint.getSignature()).getMethod());
        Object[] args = joinPoint.getArgs();
        for (int index = 0; index < args.length; index++) {
            context.setVariable("p" + index, args[index]);
            if (parameterNames != null && index < parameterNames.length
                    && parameterNames[index] != null) {
                context.setVariable(parameterNames[index], args[index]);
            }
        }
        return context;
    }

    public AuditTarget resolveTarget(StandardEvaluationContext context, Audited audited,
                                   SecurityUsers principal) {
        UUID entityId = asUuid(evaluate(context, audited.entityId()));
        String entityName = asString(evaluate(context, audited.entityName()));
        SecurityUsers scope = principal == null ? SecurityUsers.anonymous() : principal;
        return new AuditTarget(audited.entityType(), entityId, entityName,
                scope.getTenantId(), scope.getCustomerId());
    }

    private Object evaluate(StandardEvaluationContext context, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (isLiteral(expression)) {
            return expression;
        }
        try {
            return expressions.computeIfAbsent(expression, parser::parseExpression).getValue(context);
        } catch (SpelEvaluationException e) {
            if (e.getMessageCode() == SpelMessage.PROPERTY_OR_FIELD_NOT_READABLE_ON_NULL) {
                return null;
            }
            throw spelFailure(expression, e);
        } catch (EvaluationException e) {
            throw spelFailure(expression, e);
        }
    }

    private static IllegalStateException spelFailure(String expression, Exception cause) {
        return new IllegalStateException("@Audited SpEL 求值失败: " + expression, cause);
    }

    private static boolean isLiteral(String value) {
        return value.indexOf('#') < 0 && value.indexOf("T(") < 0;
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
