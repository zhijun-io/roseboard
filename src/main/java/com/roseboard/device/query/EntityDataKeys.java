package com.roseboard.device.query;

import com.roseboard.device.attribute.AttributeScope;

import java.util.List;

public record EntityDataKeys(
        List<String> telemetryKeys,
        List<String> clientAttributeKeys,
        List<String> serverAttributeKeys,
        List<String> sharedAttributeKeys) {

    public static EntityDataKeys telemetryOnly(List<String> keys) {
        return new EntityDataKeys(keys, List.of(), List.of(), List.of());
    }

    public boolean hasTelemetryKey(String key) {
        return telemetryKeys().contains(key);
    }

    public boolean hasAttributeKey(AttributeScope scope, String key) {
        return switch (scope) {
            case CLIENT -> clientAttributeKeys().contains(key);
            case SERVER -> serverAttributeKeys().contains(key);
            case SHARED -> sharedAttributeKeys().contains(key);
        };
    }
}
