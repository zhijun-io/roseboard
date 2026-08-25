package com.roseboard.cache;

import com.roseboard.infrastructure.cache.eviction.CacheEviction;
import com.roseboard.infrastructure.cache.eviction.CacheEvictionSource;

import java.util.List;

public record DeviceCredentialsCacheEvictionEvent(List<String> keys, Long version) implements CacheEvictionSource {
    public DeviceCredentialsCacheEvictionEvent {
        keys = List.copyOf(keys);
    }

    @Override
    public CacheEviction asEviction() {
        return new CacheEviction("deviceCredentials", keys, version);
    }
}
