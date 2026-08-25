package com.roseboard.infrastructure.cache.store.redis;

import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisCacheStoreIT {

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    private static final CacheSpec SPEC = new CacheSpec("devices", true, Duration.ofMinutes(5), 10);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisCacheStore backend;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                valkey.getHost(), valkey.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        backend = new RedisCacheStore(redis);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void putGetAndEvict() {
        backend.put(SPEC, "device-key", new byte[]{1, 2, 3});

        assertThat(backend.get(SPEC, "device-key")).containsExactly((byte) 1, (byte) 2, (byte) 3);

        backend.evict(SPEC, "device-key");

        assertThat(backend.get(SPEC, "device-key")).isNull();
        assertThat(redis.hasKey("{device-key}")).isFalse();
        assertThat(redis.hasKey("{device-key}:version-fence")).isFalse();
    }

    @Test
    void versionedEvictRejectsOlderFill() {
        backend.evict(SPEC, "device-key", 2L);
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{1}, 1L);

        assertThat(transaction.commit()).isFalse();
        assertThat(backend.get(SPEC, "device-key")).isNull();
    }

    @Test
    void versionedEvictAllowsNewerFill() {
        backend.evict(SPEC, "device-key", 2L);
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{3}, 3L);

        assertThat(transaction.commit()).isTrue();
        assertThat(backend.get(SPEC, "device-key")).containsExactly((byte) 3);
    }

    @Test
    void olderVersionedEvictPreservesNewerValue() {
        CacheStoreTransaction initial = backend.beginTransaction(SPEC, "device-key");
        initial.put(new byte[]{5}, 5L);
        assertThat(initial.commit()).isTrue();

        backend.evict(SPEC, "device-key", 4L);

        assertThat(backend.get(SPEC, "device-key")).containsExactly((byte) 5);
    }
}
