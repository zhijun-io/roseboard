package com.roseboard.cache;

import com.roseboard.infrastructure.cache.eviction.CacheEviction;
import com.roseboard.infrastructure.cache.eviction.CacheEvictionSource;

import java.util.List;
import java.util.UUID;

public record DeviceCacheEvictionEvent(UUID tenantId, UUID deviceId, Long version) implements CacheEvictionSource {
    @Override
    public CacheEviction asEviction() {
        return new CacheEviction("devices",
                List.of(CacheKeyBuilder.device(tenantId, deviceId)), version);
    }
}
