package com.roseboard.infrastructure.transport.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "roseboard.transport.cluster", name = "session-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTransportSessionStore implements TransportSessionStore {
    private final Map<UUID, TransportSessionRoute> routes = new ConcurrentHashMap<>();

    @Override
    public void save(TransportSessionRoute route) {
        routes.put(route.deviceId(), route);
    }

    @Override
    public void remove(UUID deviceId) {
        routes.remove(deviceId);
    }

    @Override
    public Optional<TransportSessionRoute> findByDeviceId(UUID deviceId) {
        return Optional.ofNullable(routes.get(deviceId));
    }

    @Override
    public void cleanupNode(String nodeId) {
        routes.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
    }
}
