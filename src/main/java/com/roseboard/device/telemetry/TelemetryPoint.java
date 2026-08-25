package com.roseboard.device.telemetry;

import java.util.UUID;

public record TelemetryPoint(
        UUID tenantId,
        UUID deviceId,
        String key,
        Object value,
        long timestampMs,
        UUID writeId) {
}
