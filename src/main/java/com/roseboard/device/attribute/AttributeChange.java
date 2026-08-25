package com.roseboard.device.attribute;

public record AttributeChange(
        AttributeKey key,
        long version,
        boolean changed) {
}
