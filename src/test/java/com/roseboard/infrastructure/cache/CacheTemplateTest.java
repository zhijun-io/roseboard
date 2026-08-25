package com.roseboard.infrastructure.cache;

import com.roseboard.infrastructure.cache.store.CacheStore;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;
import com.roseboard.infrastructure.cache.store.caffeine.CaffeineCacheStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheTemplateTest {

    private static final CacheSpec SPEC = new CacheSpec("devices", true, Duration.ofMinutes(5), 10);
    private static final CacheCodec<String> STRINGS = new CacheCodec<>() {
        @Override
        public byte[] encode(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] value) {
            return new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    };

    @Test
    void cacheMissLoadsOnceAndSecondReadUsesCache() {
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate service = new CacheTemplate(backend);
        AtomicInteger loads = new AtomicInteger();

        assertThat(service.get(SPEC, "device-key", () -> {
            loads.incrementAndGet();
            return "device";
        }, STRINGS)).isEqualTo("device");
        assertThat(service.get(SPEC, "device-key", () -> {
            loads.incrementAndGet();
            return "other";
        }, STRINGS)).isEqualTo("device");

        assertThat(loads).hasValue(1);
    }

    @Test
    void evictionInvalidatesInFlightCaffeineFill() {
        CaffeineCacheStore backend = new CaffeineCacheStore();
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{1});

        backend.evict(SPEC, "device-key");

        assertThat(transaction.commit()).isFalse();
        assertThat(backend.get(SPEC, "device-key")).isNull();
    }


    @Test
    void versionFenceRejectsOlderCaffeineFill() {
        CaffeineCacheStore backend = new CaffeineCacheStore();
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{1}, 1L);

        backend.evict(SPEC, "device-key", 2L);

        assertThat(transaction.commit()).isFalse();
        assertThat(backend.get(SPEC, "device-key")).isNull();
    }

    @Test
    void versionFenceAcceptsNewerCaffeineFill() {
        CaffeineCacheStore backend = new CaffeineCacheStore();
        backend.evict(SPEC, "device-key", 2L);
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{3}, 3L);

        assertThat(transaction.commit()).isTrue();
        assertThat(backend.get(SPEC, "device-key")).containsExactly((byte) 3);
    }

    @Test
    void olderVersionedEvictionPreservesNewerCaffeineValue() {
        CaffeineCacheStore backend = new CaffeineCacheStore();
        CacheStoreTransaction initial = backend.beginTransaction(SPEC, "device-key");
        initial.put(new byte[]{5}, 5L);
        assertThat(initial.commit()).isTrue();
        CacheStoreTransaction transaction = backend.beginTransaction(SPEC, "device-key");
        transaction.put(new byte[]{6}, 6L);
        assertThat(transaction.commit()).isTrue();
        backend.evict(SPEC, "device-key", 5L);

        assertThat(backend.get(SPEC, "device-key")).containsExactly((byte) 6);
    }

    @Test
    void disabledCacheAlwaysFallsBackToLoader() {
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate service = new CacheTemplate(backend);
        CacheSpec disabled = new CacheSpec("devices", true, Duration.ofMinutes(5), 0);
        AtomicInteger loads = new AtomicInteger();

        assertThat(service.get(disabled, "device-key", () -> "value-" + loads.incrementAndGet(), STRINGS))
                .isEqualTo("value-1");
        assertThat(service.get(disabled, "device-key", () -> "value-" + loads.incrementAndGet(), STRINGS))
                .isEqualTo("value-2");

        assertThat(loads).hasValue(2);
        assertThat(backend.values).isEmpty();
    }

    @Test
    void loaderFailureDoesNotLeaveAValueInCache() {
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate service = new CacheTemplate(backend);

        assertThatThrownBy(() -> service.get(SPEC, "device-key", () -> {
            throw new IllegalStateException("database failed");
        }, STRINGS)).isInstanceOf(IllegalStateException.class);

        assertThat(backend.values).isEmpty();
    }

    @Test
    void backendReadOrDecodeFailureFallsBackToLoader() {
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate service = new CacheTemplate(backend);
        AtomicInteger loads = new AtomicInteger();
        backend.values.put("device-key", new byte[]{0x02});

        assertThat(service.get(SPEC, "device-key", () -> {
            loads.incrementAndGet();
            return "database-value";
        }, STRINGS)).isEqualTo("database-value");

        assertThat(loads).hasValue(1);
        assertThat(backend.evictions).containsExactly("device-key");
    }

    @Test
    void cacheWriteFailureStillReturnsDatabaseValue() {
        RecordingBackend backend = new RecordingBackend();
        backend.failWrites = true;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheTemplate service = new CacheTemplate(backend, registry);

        assertThat(service.get(SPEC, "device-key", () -> "database-value", STRINGS))
                .isEqualTo("database-value");
        assertThat(backend.values).isEmpty();
        assertThat(registry.get("roseboard.cache.failure")
                .tag("operation", "fill")
                .tag("spec", "devices")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void readFailureThenFillFailureStillReturnsDatabaseValue() {
        RecordingBackend backend = new RecordingBackend();
        backend.failReads = true;
        backend.failWrites = true;
        CacheTemplate service = new CacheTemplate(backend);

        assertThat(service.get(SPEC, "device-key", () -> "database-value", STRINGS))
                .isEqualTo("database-value");
    }

    @Test
    void backendReadFailureRecordsMetricAndFallsBackToLoader() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingBackend backend = new RecordingBackend();
        backend.failReads = true;
        CacheTemplate service = new CacheTemplate(backend, registry);

        assertThat(service.get(SPEC, "device-key", () -> "database-value", STRINGS)).isEqualTo("database-value");
        assertThat(registry.get("roseboard.cache.failure")
                .tag("operation", "read")
                .tag("spec", "devices")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("roseboard.cache.lookup")
                .tag("result", "miss")
                .tag("spec", "devices")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void recordsHitAndMissLookups() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate service = new CacheTemplate(backend, registry);

        service.get(SPEC, "device-key", () -> "device", STRINGS);
        service.get(SPEC, "device-key", () -> "other", STRINGS);

        assertThat(registry.get("roseboard.cache.lookup")
                .tag("result", "miss")
                .tag("spec", "devices")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("roseboard.cache.lookup")
                .tag("result", "hit")
                .tag("spec", "devices")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static final class RecordingBackend implements CacheStore {
        private final Map<String, byte[]> values = new HashMap<>();
        private final java.util.List<String> evictions = new java.util.ArrayList<>();
        private boolean failReads;
        private boolean failWrites;

        @Override
        public byte[] get(CacheSpec spec, String key) {
            if (failReads) {
                throw new IllegalStateException("cache unavailable");
            }
            return values.get(key);
        }

        @Override
        public void put(CacheSpec spec, String key, byte[] value) {
            if (failWrites) {
                throw new IllegalStateException("cache unavailable");
            }
            values.put(key, value);
        }

        @Override
        public void evict(CacheSpec spec, String key) {
            evictions.add(key);
            values.remove(key);
        }
    }
}
