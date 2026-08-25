package com.roseboard.infrastructure.cache.store;

import com.roseboard.infrastructure.cache.CacheSpec;

import java.util.Objects;

final class DirectCacheStoreTransaction implements CacheStoreTransaction {
    private final CacheStore backend;
    private final CacheSpec spec;
    private final String key;
    private byte[] value;
    private boolean completed;

    DirectCacheStoreTransaction(CacheStore backend, CacheSpec spec, String key) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public void put(byte[] value) {
        if (completed) {
            throw new IllegalStateException("cache transaction is complete");
        }
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean commit() {
        if (completed) {
            return false;
        }
        completed = true;
        if (value != null) {
            backend.put(spec, key, value);
        }
        return true;
    }

    @Override
    public void rollback() {
        completed = true;
        value = null;
    }
}
