package com.roseboard.infrastructure.cache.store.redis;

import com.roseboard.infrastructure.cache.CacheSpec;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RedisCacheStoreTransactionTest {

    private static final CacheSpec SPEC = new CacheSpec("devices", true, Duration.ofMinutes(5), 10);

    @Test
    void versionFenceRejectsUnversionedFill() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisCacheStoreTransaction transaction = new RedisCacheStoreTransaction(
                connection, new byte[]{1}, new byte[]{2}, SPEC, 2L);

        transaction.put(new byte[]{3}, null);

        assertThat(transaction.commit()).isFalse();
        verify(connection).discard();
        verify(connection).close();
        verifyNoMoreInteractions(connection);
    }
}
