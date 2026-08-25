package com.roseboard.infrastructure.transport.cluster;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "roseboard.transport.cluster", name = "session-store", havingValue = "redis")
public class RedisTransportSessionStore implements TransportSessionStore {
    private static final Logger log = LoggerFactory.getLogger(RedisTransportSessionStore.class);

    private final StringRedisTemplate redis;
    private final TransportProperties properties;

    public RedisTransportSessionStore(StringRedisTemplate redis, TransportProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @PostConstruct
    void cleanupLocalNodeSessions() {
        cleanupNode(properties.getCluster().resolvedNodeId());
    }

    @Override
    public void save(TransportSessionRoute route) {
        String key = sessionKey(route.deviceId());
        redis.opsForValue().set(key, com.roseboard.common.JacksonUtils.toString(route), sessionTtl());
    }

    @Override
    public void remove(UUID deviceId) {
        redis.delete(sessionKey(deviceId));
    }

    @Override
    public Optional<TransportSessionRoute> findByDeviceId(UUID deviceId) {
        String raw = redis.opsForValue().get(sessionKey(deviceId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(com.roseboard.common.JacksonUtils.fromString(raw, TransportSessionRoute.class));
    }

    @Override
    public void cleanupNode(String nodeId) {
        Set<String> keys = redis.keys(keyPrefix() + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int removed = 0;
        for (String key : keys) {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            TransportSessionRoute route = com.roseboard.common.JacksonUtils.fromString(raw, TransportSessionRoute.class);
            if (nodeId.equals(route.nodeId())) {
                redis.delete(key);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed {} stale transport session routes for node {}", removed, nodeId);
        }
    }

    private Duration sessionTtl() {
        return Duration.ofSeconds(Math.max(60L, properties.getCluster().getSessionTtlSeconds()));
    }

    private String sessionKey(UUID deviceId) {
        return keyPrefix() + deviceId;
    }

    private String keyPrefix() {
        return properties.getCluster().getSessionKeyPrefix();
    }
}
