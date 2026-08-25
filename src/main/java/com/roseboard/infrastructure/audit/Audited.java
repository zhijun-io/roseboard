package com.roseboard.infrastructure.audit;

import com.roseboard.common.security.SecurityUserProvider;
import com.roseboard.infrastructure.audit.aspect.AuditAspect;
import com.roseboard.infrastructure.audit.event.EntityType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式审计：方法成功/失败后由 {@link AuditAspect} 尽力落库，失败不影响业务。
 * SpEL 可从参数、{@code #result}、{@code #principal} 取值；action 的 SpEL 在方法执行前求值。
 * 租户/客户 scope 取自 {@link SecurityUserProvider}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    String action();

    EntityType entityType();

    String entityId() default "";

    String entityName() default "";

}
