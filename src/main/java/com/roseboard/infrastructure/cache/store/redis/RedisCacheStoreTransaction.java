package com.roseboard.infrastructure.cache.store.redis;

import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class RedisCacheStoreTransaction implements CacheStoreTransaction {
    private final RedisConnection connection;
    private final byte[] rawKey;
    private final byte[] rawVersionKey;
    private final Expiration expiration;
    private final Long fenceVersion;
    private boolean rejected;
    private boolean completed;

    RedisCacheStoreTransaction(RedisConnection connection, byte[] rawKey, byte[] rawVersionKey,
                          CacheSpec spec, Long fenceVersion) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.rawKey = Objects.requireNonNull(rawKey, "rawKey");
        this.rawVersionKey = Objects.requireNonNull(rawVersionKey, "rawVersionKey");
        this.expiration = Expiration.from(spec.timeToLive().toMillis(), TimeUnit.MILLISECONDS);
        this.fenceVersion = fenceVersion;
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
        if (fenceVersion != null && (version == null || version <= fenceVersion)) {
            rejected = true;
            return;
        }
        connection.stringCommands().set(rawKey, Base64.getEncoder().encode(value), expiration,
                RedisStringCommands.SetOption.UPSERT);
        if (version != null) {
            connection.stringCommands().set(rawVersionKey,
                    String.valueOf(version).getBytes(StandardCharsets.UTF_8), expiration,
                    RedisStringCommands.SetOption.UPSERT);
        }
    }

    @Override
    public boolean commit() {
        if (completed) {
            return false;
        }
        if (rejected) {
            rollback();
            return false;
        }
        try {
            List<Object> result = connection.exec();
            return result != null && !result.isEmpty();
        } finally {
            completed = true;
            connection.close();
        }
    }

    @Override
    public void rollback() {
        if (completed) {
            return;
        }
        try {
            connection.discard();
        } finally {
            completed = true;
            connection.close();
        }
    }
}
