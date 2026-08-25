package com.roseboard.infrastructure.security.oauth2;

import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.jwt.SecurityErrorHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;

import java.io.IOException;

public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final SecurityEventRecorder eventRecorder;
    private final SecurityErrorHandler errorHandler;

    public OAuth2AuthenticationFailureHandler(SecurityEventRecorder eventRecorder,
                                             SecurityErrorHandler errorHandler) {
        this.eventRecorder = eventRecorder;
        this.errorHandler = errorHandler;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String registrationId = oauthRegistrationId(request);
        String detail = registrationId == null ? null : "{\"registrationId\":\"" + registrationId + "\"}";
        eventRecorder.recordLoginFailure(null, registrationId, detail, "OAuth2 authentication failed");
        errorHandler.commence(request, response, exception);
    }

    private static String oauthRegistrationId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!StringUtils.hasText(uri)) {
            return null;
        }
        int marker = uri.indexOf("/login/oauth2/code/");
        if (marker < 0) {
            return null;
        }
        String tail = uri.substring(marker + "/login/oauth2/code/".length());
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }
}
