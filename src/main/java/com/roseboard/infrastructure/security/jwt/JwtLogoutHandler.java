package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.SecurityUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
@Component
public class JwtLogoutHandler implements LogoutHandler {
    private final JwtTokenFactory tokenService;
    private final SecurityEventRecorder eventRecorder;
    private final JwtDecoder jwtDecoder;
    private final SecurityUserService identityProvider;
    private final ObjectMapper objectMapper;

    public JwtLogoutHandler(JwtTokenFactory tokenService, SecurityEventRecorder eventRecorder,
                            JwtDecoder jwtDecoder, SecurityUserService identityProvider,
                            ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.eventRecorder = eventRecorder;
        this.jwtDecoder = jwtDecoder;
        this.identityProvider = identityProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        revokeRefreshToken(request);
        SecurityUser principal = principal(request, authentication);
        if (principal == null) {
            return;
        }
        tokenService.revokeAll(principal.getUserId());
        eventRecorder.recordLogout(principal);
    }

    private SecurityUser principal(HttpServletRequest request, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser securityUser) {
            return securityUser;
        }
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof SecurityUser securityUser) {
            return securityUser;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring("Bearer ".length()));
            return identityProvider.loadUserByUsername(jwt.getSubject());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
    private void revokeRefreshToken(HttpServletRequest request) {
        try {
            JsonNode body = objectMapper.readTree(request.getInputStream());
            String refreshToken = body == null ? null : body.path("refreshToken").asText(null);
            if (refreshToken != null && !refreshToken.isBlank()) {
                tokenService.revoke(refreshToken);
            }
        } catch (Exception ignored) {
            // Authentication-based revocation remains available when no JSON body is present.
        }
    }
}
