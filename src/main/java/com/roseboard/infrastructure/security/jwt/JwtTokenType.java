package com.roseboard.infrastructure.security.jwt;

/** Wire values used by the security JWT {@code tokenType} claim. */
public enum JwtTokenType {
    ACCESS("access"),
    REFRESH("refresh"),
    MFA_CONFIGURATION("MFA_CONFIGURATION_TOKEN"),
    MFA_PRE_VERIFICATION("MFA_PRE_VERIFICATION");

    private final String value;

    JwtTokenType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
