package com.roseboard.infrastructure.security.jwt;

import com.roseboard.common.JacksonUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

public class RestLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {
    /** 认证失败审计用：attemptAuthentication 解析出的尝试登录名，写入 request attribute。 */
    public static final String AUDIT_USERNAME_ATTRIBUTE = "roseboard.auditUsername";

    private final AuthenticationSuccessHandler successHandler;
    private final AuthenticationFailureHandler failureHandler;

    public RestLoginAuthenticationFilter(String processUrl, AuthenticationManager authenticationManager,
                                        AuthenticationSuccessHandler successHandler,
                                         AuthenticationFailureHandler failureHandler) {
        super(processUrl);
        setAuthenticationManager(authenticationManager);
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException, IOException, ServletException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            throw new AuthenticationServiceException("Authentication method not supported");
        }
        LoginRequest login;
        try {
            login = JacksonUtils.readValue(request.getReader(), LoginRequest.class);
        } catch (Exception exception) {
            throw new AuthenticationServiceException("Invalid login request payload", exception);
        }
        if (login.username() == null || login.username().isBlank()
                || login.password() == null || login.password().isEmpty()) {
            throw new AuthenticationServiceException("Username or Password not provided");
        }
        request.setAttribute(AUDIT_USERNAME_ATTRIBUTE, login.username());
        return getAuthenticationManager().authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain, Authentication authResult)
            throws IOException, ServletException {
        successHandler.onAuthenticationSuccess(request, response, authResult);
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                              AuthenticationException failed)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        failureHandler.onAuthenticationFailure(request, response, failed);
    }

    record LoginRequest(String username, String password) {
    }
}
