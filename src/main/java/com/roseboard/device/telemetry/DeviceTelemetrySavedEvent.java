package com.roseboard.device.telemetry;

import java.util.UUID;

public record DeviceTelemetrySavedEvent(
        UUID tenantId,
        UUID deviceId,
        String key,
        Object value,
        long timestampMs) {
}
