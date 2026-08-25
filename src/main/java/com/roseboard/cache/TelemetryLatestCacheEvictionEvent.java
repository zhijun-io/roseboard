package com.roseboard.cache;

import com.roseboard.infrastructure.cache.eviction.CacheEviction;
import com.roseboard.infrastructure.cache.eviction.CacheEvictionSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TelemetryLatestCacheEvictionEvent(UUID tenantId, UUID deviceId, String key,
                                                Long version) implements CacheEvictionSource {
    public TelemetryLatestCacheEvictionEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(key, "key");
    }

    @Override
    public CacheEviction asEviction() {
        return new CacheEviction("tsLatest",
                List.of(CacheKeyBuilder.telemetryLatest(tenantId, deviceId, key)), version);
    }
}
