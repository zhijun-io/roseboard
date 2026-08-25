package com.roseboard.device.attribute;

import java.nio.charset.StandardCharsets;

public record AttributeKey(String value) {
    public AttributeKey {
        if (value == null || value.isBlank() || value.length() > 128
                || value.getBytes(StandardCharsets.UTF_8).length > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid attribute key");
        }
    }
}
