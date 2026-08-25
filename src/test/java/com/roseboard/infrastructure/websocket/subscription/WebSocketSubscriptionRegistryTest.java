package com.roseboard.infrastructure.websocket.subscription;

import com.roseboard.infrastructure.websocket.WebSocketSessionRef;
import com.roseboard.infrastructure.websocket.WebSocketSessionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketSubscriptionRegistryTest {
    @Test
    void resubscribeWithSameCmdIdReplacesPreviousSubscription() {
        WebSocketSubscriptionRegistry registry = new WebSocketSubscriptionRegistry();
        UUID deviceId = UUID.randomUUID();
        WebSocketSessionRef session = new WebSocketSessionRef(
                "session-1", null, null, null, WebSocketSessionType.GENERAL);

        registry.registerTimeseries(session, 7, deviceId, Set.of("temp"));
        registry.registerTimeseries(session, 7, deviceId, Set.of("humidity"));

        List<WebSocketSubscriptionRegistry.MatchedUpdate> matched =
                registry.matchTelemetry(deviceId, "humidity", 1L, 42);

        assertEquals(1, matched.size());
        assertEquals(7, matched.getFirst().update().getSubscriptionId());
    }
}
