package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

public class RefreshTokenAuthenticationFilter extends OncePerRequestFilter {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenFactory tokenService;
    private final ObjectMapper objectMapper;
    private final SecurityErrorHandler errorHandler;

    public RefreshTokenAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtTokenFactory tokenService, ObjectMapper objectMapper,
            SecurityErrorHandler errorHandler) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/token".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            errorHandler.commence(request, response,
                    new AuthenticationServiceException(
                            "Authentication method not supported"));
            return;
        }
        try {
            RefreshTokenRequest refresh = objectMapper.readValue(request.getReader(), RefreshTokenRequest.class);
            RefreshTokenAuthenticationProvider.RefreshTokenAuthenticationResult authentication =
                    (RefreshTokenAuthenticationProvider.RefreshTokenAuthenticationResult)
                            authenticationManager.authenticate(
                                    new RefreshTokenAuthenticationToken(refresh.refreshToken()));
            SecurityUser principal = authentication.user();
            LoginResponse result = tokenService.login(principal);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), result);
        } catch (Exception exception) {
            errorHandler.commence(request, response,
                    new AuthenticationServiceException(
                            exception.getMessage(), exception));
        }
    }

    record RefreshTokenRequest(String refreshToken) {
    }
}
