package com.roseboard.infrastructure.cache.eviction;

import java.util.List;
import java.util.Objects;

public record CacheEviction(String specName, List<String> keys, Long version) implements CacheEvictionSource {
    public CacheEviction(String specName, List<String> keys) {
        this(specName, keys, null);
    }

    public CacheEviction {
        Objects.requireNonNull(specName, "specName");
        keys = List.copyOf(keys);
        if (version != null && version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    @Override
    public CacheEviction asEviction() {
        return this;
    }
}
