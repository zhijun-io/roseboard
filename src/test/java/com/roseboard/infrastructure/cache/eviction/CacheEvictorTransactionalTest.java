package com.roseboard.infrastructure.cache.eviction;

import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.store.CacheStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CacheEvictorTransactionalTest.TestConfig.class)
class CacheEvictorTransactionalTest {

    @Autowired
    private CacheEvictor evictions;

    @Autowired
    private RecordingBackend backend;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearBackend() {
        backend.evicted.clear();
        backend.values.clear();
    }

    @Test
    void evictsAfterCommitNotBefore() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            evictions.publish(new CacheEviction("devices", List.of("key-1", "key-2")));
            assertThat(backend.evicted).isEmpty();
        });

        assertThat(backend.evicted).containsExactly("key-1", "key-2");
    }

    @Test
    void rollbackSkipsEviction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            evictions.publish(new CacheEviction("devices", List.of("key-1")));
            status.setRollbackOnly();
        });

        assertThat(backend.evicted).isEmpty();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        RecordingBackend backend() {
            return new RecordingBackend();
        }

        @Bean
        CacheTemplate cacheAside(RecordingBackend backend) {
            return new CacheTemplate(backend);
        }

        @Bean
        CacheProperties cacheProperties() {
            return new CacheProperties();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }

        @Bean
        CacheEvictor cacheEvictions(CacheTemplate cache, CacheProperties properties,
                                            org.springframework.context.ApplicationEventPublisher events) {
            return new CacheEvictor(cache, properties, events);
        }
    }

    static final class RecordingBackend implements CacheStore {
        private final List<String> evicted = new ArrayList<>();
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public byte[] get(CacheSpec spec, String key) {
            return values.get(key);
        }

        @Override
        public void put(CacheSpec spec, String key, byte[] value) {
            values.put(key, value);
        }

        @Override
        public void evict(CacheSpec spec, String key) {
            evicted.add(key);
            values.remove(key);
        }

        @Override
        public void evict(CacheSpec spec, String key, Long version) {
            evict(spec, key);
        }
    }
}
