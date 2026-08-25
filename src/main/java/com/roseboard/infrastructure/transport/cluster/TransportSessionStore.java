package com.roseboard.infrastructure.transport.cluster;

import java.util.Optional;
import java.util.UUID;

public interface TransportSessionStore {
    void save(TransportSessionRoute route);

    void remove(UUID deviceId);

    Optional<TransportSessionRoute> findByDeviceId(UUID deviceId);

    void cleanupNode(String nodeId);
}
