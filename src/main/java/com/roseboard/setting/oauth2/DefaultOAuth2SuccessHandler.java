package com.roseboard.setting.oauth2;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.infrastructure.security.jwt.mfa.MfaLoginPolicy;
import com.roseboard.infrastructure.security.oauth2.OAuth2LoginSuccessHandler;
import com.roseboard.infrastructure.security.oauth2.OAuth2ResolutionException;
import com.roseboard.infrastructure.security.oauth2.OAuth2SecurityUserProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.IOException;

public class DefaultOAuth2SuccessHandler implements OAuth2LoginSuccessHandler {
    private final OAuth2SecurityUserProvider oAuth2SecurityUserProvider;
    private final MfaLoginPolicy mfaLoginPolicy;
    private final JwtTokenFactory jwtTokenFactory;
    private final SecurityEventRecorder securityEventRecorder;

    public DefaultOAuth2SuccessHandler(OAuth2SecurityUserProvider oAuth2SecurityUserProvider,
                                       MfaLoginPolicy mfaLoginPolicy, JwtTokenFactory jwtTokenFactory,
                                       SecurityEventRecorder securityEventRecorder) {
        this.oAuth2SecurityUserProvider = oAuth2SecurityUserProvider;
        this.mfaLoginPolicy = mfaLoginPolicy;
        this.jwtTokenFactory = jwtTokenFactory;
        this.securityEventRecorder = securityEventRecorder;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        SecurityUser details;
        try {
            details = oAuth2SecurityUserProvider.resolve(oauth.getAuthorizedClientRegistrationId(), oauthUser);
        } catch (OAuth2ResolutionException exception) {
            securityEventRecorder.recordLoginFailure(null, null, null, exception.getMessage());
            response.sendError(exception.status(), exception.getMessage());
            return;
        }
        if (!details.isEnabled()) {
            securityEventRecorder.recordLoginFailure(details, details.getUsername(), null,
                    "OAuth2 user is not activated");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "OAuth2 user is not activated");
            return;
        }
        LoginResponse result;
        boolean fullLogin = false;
        if (mfaLoginPolicy.requiresConfiguration(details.getUserId())) {
            result = jwtTokenFactory.issueMfaConfiguration(details);
        } else if (mfaLoginPolicy.requiresVerification(details.getUserId())) {
            result = jwtTokenFactory.issuePreVerification(details);
        } else {
            result = jwtTokenFactory.login(details);
            fullLogin = true;
        }
        if (fullLogin) {
            securityEventRecorder.recordLoginSuccess(details);
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        JacksonUtils.writeValue(response.getWriter(), result);
    }
}
