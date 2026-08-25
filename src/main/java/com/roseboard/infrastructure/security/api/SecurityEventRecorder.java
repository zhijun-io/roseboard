package com.roseboard.infrastructure.security.api;

/** Host hook for recording authentication lifecycle events. */
public interface SecurityEventRecorder {
    void recordLoginSuccess(SecurityUser principal);

    void recordLoginFailure(SecurityUser principal, String subject, String detail, String reason);

    void recordLogout(SecurityUser principal);
}
