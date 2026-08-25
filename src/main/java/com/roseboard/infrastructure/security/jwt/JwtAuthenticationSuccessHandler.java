package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.jwt.mfa.MfaLoginPolicy;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.api.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static com.roseboard.infrastructure.security.SecurityAutoConfiguration.PUBLIC_LOGIN_ENTRY_POINT;

public class JwtAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final JwtTokenFactory tokenService;
    private final MfaLoginPolicy mfaPolicy;
    private final ObjectMapper objectMapper;
    private final SecurityEventRecorder eventRecorder;

    public JwtAuthenticationSuccessHandler(JwtTokenFactory tokenService, MfaLoginPolicy mfaPolicy,
                                           ObjectMapper objectMapper,
                                           SecurityEventRecorder eventRecorder) {
        this.tokenService = tokenService;
        this.mfaPolicy = mfaPolicy;
        this.objectMapper = objectMapper;
        this.eventRecorder = eventRecorder;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        SecurityUser user = (SecurityUser) authentication.getPrincipal();
        LoginResponse result;
        boolean fullLogin = false;
        if (mfaPolicy.requiresConfiguration(user.getUserId())) {
            result = tokenService.issueMfaConfiguration(user);
        } else if (mfaPolicy.requiresVerification(user.getUserId())) {
            result = tokenService.issuePreVerification(user);
        } else {
            boolean publicLogin = PUBLIC_LOGIN_ENTRY_POINT.equals(request.getServletPath())
                    || request.getRequestURI().endsWith(PUBLIC_LOGIN_ENTRY_POINT);
            result = tokenService.login(user, publicLogin);
            fullLogin = true;
        }
        if (fullLogin) {
            eventRecorder.recordLoginSuccess(user);
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), result);
    }
}
