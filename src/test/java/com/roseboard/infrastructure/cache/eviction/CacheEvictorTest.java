package com.roseboard.infrastructure.cache.eviction;

import com.roseboard.cache.DeviceCredentialsCacheEvictionEvent;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.store.CacheStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEvictorTest {

    @Test
    void publishesOneEvictionEventForAllKeysWhenNoTransactionIsActive() {
        RecordingBackend backend = new RecordingBackend();
        CacheTemplate cache = new CacheTemplate(backend);
        CacheProperties properties = new CacheProperties();
        ApplicationEventPublisher unexpectedPublisher = event -> {
            throw new AssertionError("event should be handled directly without a transaction");
        };
        CacheEvictor evictions = new CacheEvictor(cache, properties, unexpectedPublisher);

        evictions.publish(new CacheEviction("devices", List.of("key-1", "key-2")));

        assertThat(backend.evicted).containsExactly("key-1", "key-2");
    }

    @Test
    void propagatesVersionFenceToBackend() {
        RecordingBackend backend = new RecordingBackend();
        CacheEvictor evictions = new CacheEvictor(
                new CacheTemplate(backend), new CacheProperties(), event -> {
        });

        evictions.publish(new CacheEviction("devices", List.of("key-1"), 7L));

        assertThat(backend.evictedVersions).containsExactly(7L);
    }

    @Test
    void emptyKeysAreIgnored() {
        RecordingBackend backend = new RecordingBackend();
        CacheEvictor evictions = new CacheEvictor(
                new CacheTemplate(backend), new CacheProperties(), event -> {
            throw new AssertionError("empty eviction should not publish");
        });

        evictions.publish(new DeviceCredentialsCacheEvictionEvent(List.of(), null));

        assertThat(backend.evicted).isEmpty();
    }

    private static final class RecordingBackend implements CacheStore {
        private final List<String> evicted = new ArrayList<>();
        private final List<Long> evictedVersions = new ArrayList<>();

        @Override
        public byte[] get(CacheSpec spec, String key) {
            return null;
        }

        @Override
        public void put(CacheSpec spec, String key, byte[] value) {
        }

        @Override
        public void evict(CacheSpec spec, String key) {
            evicted.add(key);
        }

        @Override
        public void evict(CacheSpec spec, String key, Long version) {
            evictedVersions.add(version);
            evict(spec, key);
        }
    }
}
