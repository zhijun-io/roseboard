package com.roseboard.infrastructure.security.jwt.mfa;

import java.util.UUID;

/** Host-provided MFA decision used by the login flow. */
public interface MfaLoginPolicy {
    boolean requiresConfiguration(UUID userId);

    boolean requiresVerification(UUID userId);
}
