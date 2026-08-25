package com.roseboard.infrastructure.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.AuthenticationException;

/** Optional host hook for recording authentication failures. */
public interface AuthenticationFailureRecorder {
    void record(HttpServletRequest request, AuthenticationException exception);
}
