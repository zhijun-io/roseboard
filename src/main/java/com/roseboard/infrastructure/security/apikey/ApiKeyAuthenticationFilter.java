package com.roseboard.infrastructure.security.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

public class ApiKeyAuthenticationFilter extends AbstractAuthenticationProcessingFilter {
    private final BearerTokenResolver tokenExtractor;

    public ApiKeyAuthenticationFilter(AuthenticationFailureHandler failureHandler,
                                      BearerTokenResolver tokenExtractor,
                                      RequestMatcher matcher) {
        super(matcher);
        this.tokenExtractor = tokenExtractor;
        setAuthenticationFailureHandler(failureHandler);
        setAuthenticationSuccessHandler((request, response, authentication) -> {
        });
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
        return getAuthenticationManager().authenticate(
                new ApiKeyAuthenticationToken(tokenExtractor.resolve(request)));
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain, Authentication authentication)
            throws IOException, ServletException {
        super.successfulAuthentication(request, response, chain, authentication);
        chain.doFilter(request, response);
    }
}
