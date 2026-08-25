package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.SecurityUserService;

import java.util.Collection;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
public final class RefreshTokenAuthenticationProvider implements AuthenticationProvider {
    private final JwtTokenFactory tokenService;
    private final SecurityUserService identityProvider;

    public RefreshTokenAuthenticationProvider(JwtTokenFactory tokenService,
                                              SecurityUserService identityProvider) {
        this.tokenService = tokenService;
        this.identityProvider = identityProvider;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        try {
            String value = (String) authentication.getCredentials();
            JwtTokenFactory.RoseboardUserToken token = tokenService.refresh(value);
            SecurityUser user = identityProvider.loadUserByUsername(token.username());
            return new RefreshTokenAuthenticationResult(user, value);
        } catch (Exception exception) {
            throw new AuthenticationServiceException("Refresh token authentication failed", exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    public record RefreshTokenAuthenticationResult(SecurityUser user, String refreshToken)
            implements Authentication {
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return user.getAuthorities(); }
        @Override public Object getCredentials() { return refreshToken; }
        @Override public Object getDetails() { return null; }
        @Override public Object getPrincipal() { return user; }
        @Override public boolean isAuthenticated() { return true; }
        @Override public void setAuthenticated(boolean authenticated) { if (!authenticated) throw new IllegalArgumentException("Cannot unauthenticate result"); }
        @Override public String getName() { return user.getUsername(); }
    }
}
