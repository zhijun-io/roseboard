package com.roseboard.infrastructure.cache.store;

public interface CacheStoreTransaction {
    default void put(byte[] value, Long version) {
        put(value);
    }
    void put(byte[] value);

    boolean commit();

    void rollback();
}
