package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {
    private final AccessJwtToken rawAccessToken;
    private final SecurityUser securityUser;

    public JwtAuthenticationToken(AccessJwtToken rawAccessToken) {
        super((Collection<GrantedAuthority>) null);
        this.rawAccessToken = rawAccessToken;
        this.securityUser = null;
        setAuthenticated(false);
    }

    public JwtAuthenticationToken(SecurityUser securityUser) {
        this(securityUser, securityUser.getAuthorities());
    }

    public JwtAuthenticationToken(SecurityUser securityUser, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.rawAccessToken = null;
        this.securityUser = securityUser;
        super.setAuthenticated(true);
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Cannot set this token to trusted - use the authenticated constructor");
        }
        super.setAuthenticated(false);
    }

    @Override
    public Object getCredentials() { return rawAccessToken; }

    @Override
    public Object getPrincipal() { return securityUser; }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
    }
}
