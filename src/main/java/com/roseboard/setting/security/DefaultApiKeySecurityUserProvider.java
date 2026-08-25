package com.roseboard.setting.security;

import com.roseboard.apikey.ApiKeyEntity;
import com.roseboard.apikey.ApiKeyMapper;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.apikey.ApiKeySecurityUserProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** MyBatis adapter for Roseboard API keys. */
@Service
public class DefaultApiKeySecurityUserProvider implements ApiKeySecurityUserProvider {
    private final ApiKeyMapper apiKeyMapper;
    private final DefaultSecurityUserService securityUserProvider;

    public DefaultApiKeySecurityUserProvider(ApiKeyMapper apiKeyMapper,
                                             DefaultSecurityUserService securityUserProvider) {
        this.apiKeyMapper = apiKeyMapper;
        this.securityUserProvider = securityUserProvider;
    }

    @Override
    public SecurityUser resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        ApiKeyEntity key = apiKeyMapper.findEnabledByHash(hash(apiKey));
        return key == null ? null : securityUserProvider.loadUserById(key.getUserId());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }
}
