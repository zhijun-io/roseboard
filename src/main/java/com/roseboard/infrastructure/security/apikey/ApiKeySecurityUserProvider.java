package com.roseboard.infrastructure.security.apikey;

import com.roseboard.infrastructure.security.api.SecurityUser;

/** Host seam for resolving an API key without exposing its persistence model. */
public interface ApiKeySecurityUserProvider {
    SecurityUser resolve(String apiKey);
}
