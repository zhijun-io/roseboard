package com.roseboard.infrastructure.transport.cluster;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportSessionStoreTest {
    private final InMemoryTransportSessionStore store = new InMemoryTransportSessionStore();

    @Test
    void savesAndFindsRouteByDeviceId() {
        UUID deviceId = UUID.randomUUID();
        TransportSessionRoute route = new TransportSessionRoute(
                deviceId, UUID.randomUUID(), UUID.randomUUID(), "node-a", "MQTT", 10L);

        store.save(route);

        assertEquals(route, store.findByDeviceId(deviceId).orElseThrow());
    }

    @Test
    void removeAndCleanupNodeDeleteRoutes() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.save(new TransportSessionRoute(first, UUID.randomUUID(), UUID.randomUUID(), "node-a", "MQTT", 10L));
        store.save(new TransportSessionRoute(second, UUID.randomUUID(), UUID.randomUUID(), "node-b", "MQTT", 10L));

        store.cleanupNode("node-a");

        assertTrue(store.findByDeviceId(first).isEmpty());
        assertTrue(store.findByDeviceId(second).isPresent());
        store.remove(second);
        assertTrue(store.findByDeviceId(second).isEmpty());
    }
}
