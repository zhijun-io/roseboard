package com.roseboard.infrastructure.security.jwt.mfa;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public interface MfaProvider {
    MfaProviderType type();
    JsonNode generate(UUID userId, String subject);
    boolean verify(UUID userId, JsonNode config, String verificationCode);
    default void send(UUID userId, String subject) {
        throw new UnsupportedOperationException("MFA provider does not support code sending: " + type());
    }
}
