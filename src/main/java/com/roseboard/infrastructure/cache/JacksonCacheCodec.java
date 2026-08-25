package com.roseboard.infrastructure.cache;

import com.roseboard.common.JacksonUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

public final class JacksonCacheCodec<T> implements CacheCodec<T> {
    private final Class<T> valueType;
    private final Function<T, Long> versionExtractor;

    public JacksonCacheCodec(Class<T> valueType) {
        this(valueType, ignored -> null);
    }

    public JacksonCacheCodec(Class<T> valueType, Function<T, Long> versionExtractor) {
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.versionExtractor = Objects.requireNonNull(versionExtractor, "versionExtractor");
    }

    @Override
    public byte[] encode(T value) {
        return JacksonUtils.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public T decode(byte[] value) {
        return JacksonUtils.fromBytes(value, valueType);
    }

    @Override
    public Long version(T value) {
        return value == null ? null : versionExtractor.apply(value);
    }
}

