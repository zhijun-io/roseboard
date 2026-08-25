package com.roseboard.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Roles persisted on a Roseboard user. */
public enum UserAuthority {
    SYS_ADMIN,
    TENANT_ADMIN,
    CUSTOMER_USER;

    @JsonCreator
    public static UserAuthority from(String value) {
        return value == null ? null : valueOf(value.toUpperCase());
    }

    @JsonValue
    public String value() {
        return name();
    }
}
