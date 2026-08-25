package com.roseboard.device.query;

import java.util.Map;
import java.util.UUID;

public record EntityDataSnapshot(
        UUID deviceId,
        Map<String, EntityTelemetryPoint> latestTelemetry,
        Map<String, Object> clientAttributes,
        Map<String, Object> serverAttributes,
        Map<String, Object> sharedAttributes) {
}
