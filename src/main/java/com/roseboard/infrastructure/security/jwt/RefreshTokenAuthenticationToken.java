package com.roseboard.infrastructure.security.jwt;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public final class RefreshTokenAuthenticationToken extends AbstractAuthenticationToken {
    private final String refreshToken;

    public RefreshTokenAuthenticationToken(String refreshToken) {
        super(List.of());
        this.refreshToken = refreshToken;
        setAuthenticated(false);
    }

    public String refreshToken() { return refreshToken; }
    @Override public Object getCredentials() { return refreshToken; }
    @Override public Object getPrincipal() { return refreshToken; }
}
