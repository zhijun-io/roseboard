package com.roseboard.device.attribute;

import com.roseboard.device.attribute.AttributeScope;

import java.util.UUID;

public record DeviceAttributeChangedEvent(
        UUID tenantId,
        UUID deviceId,
        AttributeScope scope,
        String key,
        Object value,
        long updatedTimeMs) {
}
