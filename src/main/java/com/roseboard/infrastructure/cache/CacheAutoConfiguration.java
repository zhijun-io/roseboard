package com.roseboard.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.store.CacheStore;
import com.roseboard.infrastructure.cache.store.CacheStoreType;
import com.roseboard.infrastructure.cache.store.caffeine.CaffeineCacheStore;
import com.roseboard.infrastructure.cache.store.redis.RedisCacheStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CacheStore.class)
    CacheStore cacheStore(CacheProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        if (properties.backend() == CacheStoreType.REDIS) {
            StringRedisTemplate template = redis.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("roseboard.cache.backend=redis requires StringRedisTemplate");
            }
            return new RedisCacheStore(template);
        }
        return new CaffeineCacheStore();
    }

    @Bean
    @ConditionalOnMissingBean(CacheTemplate.class)
    CacheTemplate cacheTemplate(CacheStore store, ObjectProvider<MeterRegistry> meterRegistry) {
        return new CacheTemplate(store, meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(CacheEvictor.class)
    CacheEvictor cacheEvictor(CacheTemplate cache, CacheProperties properties,
                              ApplicationEventPublisher events) {
        return new CacheEvictor(cache, properties, events);
    }
}
