package com.roseboard.infrastructure.cache;

public interface CacheCodec<T> {
    byte[] encode(T value);

    T decode(byte[] value);

    default Long version(T value) {
        return null;
    }
}
