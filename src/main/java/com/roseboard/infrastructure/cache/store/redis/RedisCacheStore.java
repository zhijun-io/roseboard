package com.roseboard.infrastructure.cache.store.redis;

import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.store.CacheStore;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class RedisCacheStore implements CacheStore {
    private static final DefaultRedisScript<Long> VERSIONED_EVICT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if (not current) or tonumber(ARGV[1]) > tonumber(current) then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.connectionFactory = Objects.requireNonNull(redis.getConnectionFactory(), "connectionFactory");
    }

    @Override
    public byte[] get(CacheSpec spec, String key) {
        String value = redis.opsForValue().get(redisKey(key));
        if (value == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            evict(spec, key);
            return null;
        }
    }

    @Override
    public void put(CacheSpec spec, String key, byte[] value) {
        redis.opsForValue().set(redisKey(key), Base64.getEncoder().encodeToString(value), spec.timeToLive());
    }

    @Override
    public void evict(CacheSpec spec, String key) {
        redis.delete(List.of(redisKey(key), versionKey(key)));
    }

    @Override
    public void evict(CacheSpec spec, String key, Long version) {
        if (version == null) {
            evict(spec, key);
            return;
        }
        redis.execute(VERSIONED_EVICT,
                List.of(versionKey(key), redisKey(key)),
                String.valueOf(version),
                String.valueOf(spec.timeToLive().toMillis()));
    }

    @Override
    public CacheStoreTransaction beginTransaction(CacheSpec spec, String key) {
        RedisConnection connection = connectionFactory.getConnection();
        try {
            byte[] rawKey = rawKey(key);
            byte[] rawVersionKey = rawVersionKey(key);
            connection.watch(rawKey, rawVersionKey);
            byte[] rawFence = connection.stringCommands().get(rawVersionKey);
            connection.multi();
            Long fenceVersion = parseFence(rawFence);
            return new RedisCacheStoreTransaction(connection, rawKey, rawVersionKey, spec, fenceVersion);
        } catch (RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static Long parseFence(byte[] rawFence) {
        if (rawFence == null || rawFence.length == 0) {
            return null;
        }
        try {
            return Long.valueOf(new String(rawFence, java.nio.charset.StandardCharsets.UTF_8));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static byte[] rawKey(String key) {
        return StringRedisSerializer.UTF_8.serialize(redisKey(key));
    }

    private static byte[] rawVersionKey(String key) {
        return StringRedisSerializer.UTF_8.serialize(versionKey(key));
    }

    private static String redisKey(String key) {
        return "{" + key + "}";
    }

    private static String versionKey(String key) {
        return redisKey(key) + ":version-fence";
    }
}
