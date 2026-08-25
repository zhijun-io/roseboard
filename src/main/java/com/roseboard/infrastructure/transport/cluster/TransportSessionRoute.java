package com.roseboard.infrastructure.transport.cluster;

import java.util.UUID;

public record TransportSessionRoute(
        UUID deviceId,
        UUID tenantId,
        UUID sessionId,
        String nodeId,
        String protocol,
        long lastActivityAt) {
}
