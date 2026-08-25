package com.roseboard.setting.security;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import com.roseboard.infrastructure.security.jwt.AuthenticationFailureRecorder;
import com.roseboard.infrastructure.security.jwt.RestLoginAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/** Roseboard audit adapter for failed credential logins. */
@Component
public class DefaultAuthenticationFailureRecorder implements AuthenticationFailureRecorder {
    private static final int MAX_ATTEMPTED_NAME = 255;

    private final ApplicationEventPublisher eventPublisher;
    private final UserMapper userMapper;

    public DefaultAuthenticationFailureRecorder(ApplicationEventPublisher eventPublisher, UserMapper userMapper) {
        this.eventPublisher = eventPublisher;
        this.userMapper = userMapper;
    }

    @Override
    public void record(HttpServletRequest request, AuthenticationException exception) {
        Object attempted = request.getAttribute(RestLoginAuthenticationFilter.AUDIT_USERNAME_ATTRIBUTE);
        if (!(attempted instanceof String username)) {
            return;
        }
        String name = username.length() <= MAX_ATTEMPTED_NAME
                ? username : username.substring(0, MAX_ATTEMPTED_NAME);
        UserEntity user = userMapper.findByEmail(name);
        AuditEvent event = user == null
                ? AuditEvent.loginFailure(AuditActions.LOGIN_FAILED, name, exception.getMessage())
                : AuditEvent.loginFailure(AuditActions.LOGIN_FAILED, name, exception.getMessage(),
                user.getTenantId(), user.getCustomerId());
        eventPublisher.publishEvent(event);
    }
}
