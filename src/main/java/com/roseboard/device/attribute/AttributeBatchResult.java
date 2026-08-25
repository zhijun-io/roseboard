package com.roseboard.device.attribute;

import java.util.List;

public record AttributeBatchResult(
        List<AttributeChange> changes,
        List<AttributeFailure> failures) {

    public AttributeBatchResult {
        changes = List.copyOf(changes);
        failures = List.copyOf(failures);
    }

    public record AttributeFailure(AttributeKey key, String reason) {
    }
}
