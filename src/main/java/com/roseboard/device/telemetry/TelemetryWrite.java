package com.roseboard.device.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryWrite(
        String key,
        Object value,
        Instant timestamp,
        UUID writeId) {
}
