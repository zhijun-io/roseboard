package com.roseboard.cache;

import com.roseboard.infrastructure.cache.eviction.CacheEviction;
import com.roseboard.infrastructure.cache.eviction.CacheEvictionSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AttributeCacheEvictionEvent(UUID tenantId, UUID deviceId, String scope,
                                          String key, Long version) implements CacheEvictionSource {
    public AttributeCacheEvictionEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
    }

    @Override
    public CacheEviction asEviction() {
        return new CacheEviction("attributes",
                List.of(CacheKeyBuilder.attribute(tenantId, deviceId, scope, key)), version);
    }
}
