package com.roseboard.infrastructure.security.api;

import com.roseboard.infrastructure.audit.event.EntityType;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@permissionChecker.hasPermission(authentication, '{resource}', '{operation}') and ({scope})")
public @interface RequirePermission {
    EntityType resource();
    Operation operation();
    String scope() default "true";
}
