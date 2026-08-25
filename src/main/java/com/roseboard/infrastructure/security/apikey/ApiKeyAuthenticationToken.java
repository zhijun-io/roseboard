package com.roseboard.infrastructure.security.apikey;

import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {
    private final String apiKey;

    public ApiKeyAuthenticationToken(String apiKey) {
        super(List.of());
        this.apiKey = apiKey;
        setAuthenticated(false);
    }

    public ApiKeyAuthenticationToken(SecurityUser user) {
        super(user.getAuthorities());
        this.apiKey = null;
        setAuthenticated(true);
        setDetails(user);
    }
    @Override public Object getCredentials() { return apiKey; }
    @Override public Object getPrincipal() { return getDetails(); }

    public String getApiKey() { return apiKey; }
}
