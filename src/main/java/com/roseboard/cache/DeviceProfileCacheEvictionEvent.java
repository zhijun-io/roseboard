package com.roseboard.cache;

import com.roseboard.infrastructure.cache.eviction.CacheEviction;
import com.roseboard.infrastructure.cache.eviction.CacheEvictionSource;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

public record DeviceProfileCacheEvictionEvent(UUID tenantId, Long version, List<String> keys)
        implements CacheEvictionSource {
    public DeviceProfileCacheEvictionEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        keys = List.copyOf(keys);
    }

    @Override
    public CacheEviction asEviction() {
        return new CacheEviction("deviceProfiles", keys, version);
    }
}
