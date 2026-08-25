package com.roseboard.infrastructure.security.apikey;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.util.StringUtils;

/** Resolves API keys from the supported API-key header forms. */
public final class ApiKeyTokenResolver implements BearerTokenResolver {
    private static final String HEADER_PREFIX = "ApiKey ";

    @Override
    public String resolve(HttpServletRequest request) {
        String token = resolveHeader(request, "X-Authorization");
        return token != null ? token : resolveHeader(request, "Authorization");
    }

    private String resolveHeader(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value == null || !value.startsWith(HEADER_PREFIX)) {
            return null;
        }
        String token = value.substring(HEADER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
