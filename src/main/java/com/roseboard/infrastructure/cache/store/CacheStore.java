package com.roseboard.infrastructure.cache.store;

import com.roseboard.infrastructure.cache.CacheSpec;

public interface CacheStore {
    byte[] get(CacheSpec spec, String key);

    void put(CacheSpec spec, String key, byte[] value);

    void evict(CacheSpec spec, String key);
    default void evict(CacheSpec spec, String key, Long version) {
        evict(spec, key);
    }

    default CacheStoreTransaction beginTransaction(CacheSpec spec, String key) {
        return new DirectCacheStoreTransaction(this, spec, key);
    }
}
