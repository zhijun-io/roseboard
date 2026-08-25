package com.roseboard.device.attribute;

import java.util.UUID;

public record DeviceAttribute(
        UUID tenantId,
        UUID deviceId,
        AttributeScope scope,
        AttributeKey key,
        AttributeValue value,
        long version,
        long updatedTime) {
}
