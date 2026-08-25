package com.roseboard.infrastructure.security.jwt;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * 认证失败处理器：JWT/API Key 过滤器与登录过滤器共用。
 * 仅当请求由 {@link RestLoginAuthenticationFilter} 处理（带尝试登录名）时记录 LOGIN_FAILED，
 * 避免令牌失效等非登录失败产生噪声审计。
 */
public class JwtAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final AuthenticationFailureRecorder recorder;
    private final SecurityErrorHandler errorHandler;

    public JwtAuthenticationFailureHandler(AuthenticationFailureRecorder recorder,
                                          SecurityErrorHandler errorHandler) {
        this.recorder = recorder;
        this.errorHandler = errorHandler;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {
        recorder.record(request, exception);
        errorHandler.commence(request, response, exception);
    }
}
