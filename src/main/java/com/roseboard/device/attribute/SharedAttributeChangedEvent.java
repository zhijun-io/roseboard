package com.roseboard.device.attribute;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SharedAttributeChangedEvent(
        UUID tenantId,
        UUID deviceId,
        Map<String, Object> updated,
        List<String> deleted) {
}
