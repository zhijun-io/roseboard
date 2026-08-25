package com.roseboard.device.attribute;

public record AttributeWrite(
        AttributeScope scope,
        AttributeKey key,
        AttributeValue value,
        Long expectedVersion) {
}
