package com.roseboard.infrastructure.cache.store.caffeine;

import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;

import java.util.Objects;

final class CaffeineCacheStoreTransaction implements CacheStoreTransaction {
    private final CaffeineCacheStore backend;
    private final CacheSpec spec;
    private final String key;
    private byte[] value;
    private Long version;
    private boolean failed;
    private boolean completed;

    CaffeineCacheStoreTransaction(CaffeineCacheStore backend, CacheSpec spec, String key) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.key = Objects.requireNonNull(key, "key");
    }

    String key() {
        return key;
    }

    void fail() {
        failed = true;
    }

    boolean failed() {
        return failed;
    }

    @Override
    public void put(byte[] value) {
        put(value, null);
    }

    @Override
    public void put(byte[] value, Long version) {
        if (completed) {
            throw new IllegalStateException("cache transaction is complete");
        }
        this.value = Objects.requireNonNull(value, "value");
        this.version = version;
    }

    @Override
    public boolean commit() {
        return backend.commit(this, value, version);
    }

    @Override
    public void rollback() {
        backend.rollback(this);
    }

    void complete() {
        completed = true;
        value = null;
        version = null;
    }

    CacheSpec spec() {
        return spec;
    }
}
