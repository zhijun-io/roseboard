package com.roseboard.infrastructure.security.jwt.mfa;

public enum MfaProviderType {
    TOTP,
    EMAIL,
    SMS,
    BACKUP_CODE
}
