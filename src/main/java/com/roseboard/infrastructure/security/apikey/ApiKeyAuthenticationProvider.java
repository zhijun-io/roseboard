package com.roseboard.infrastructure.security.apikey;

import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;

public class ApiKeyAuthenticationProvider implements AuthenticationProvider {
    private final ApiKeySecurityUserProvider identityProvider;

    public ApiKeyAuthenticationProvider(ApiKeySecurityUserProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    public SecurityUser authenticateApiKey(String apiKey) throws AuthenticationException {
        Authentication authentication = authenticate(new ApiKeyAuthenticationToken(apiKey));
        return (SecurityUser) authentication.getPrincipal();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        SecurityUser principal = identityProvider.resolve((String) authentication.getCredentials());
        if (principal == null) throw new BadCredentialsException("Invalid API key");
        return new ApiKeyAuthenticationToken(principal);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
