package com.roseboard.infrastructure.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    private final ExposedJwtAuthenticationFilter filter = new ExposedJwtAuthenticationFilter();

    @Test
    void requiresAuthenticationOnlyWhenBearerTokenExists() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(filter.requires(request, response));

        request.addHeader("Authorization", "Bearer token");
        assertTrue(filter.requires(request, response));
    }

    @Test
    void doesNotClaimApiKeyRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Authorization", "ApiKey key-123");

        assertFalse(filter.requires(request, new MockHttpServletResponse()));
    }

    private static final class ExposedJwtAuthenticationFilter extends JwtAuthenticationFilter {
        private ExposedJwtAuthenticationFilter() {
            super(mock(AuthenticationFailureHandler.class), new DefaultBearerTokenResolver(),
                    AnyRequestMatcher.INSTANCE);
        }

        private boolean requires(MockHttpServletRequest request, MockHttpServletResponse response) {
            return requiresAuthentication(request, response);
        }
    }
}
