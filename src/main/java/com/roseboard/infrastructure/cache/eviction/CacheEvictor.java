package com.roseboard.infrastructure.cache.eviction;

import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.CacheTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes cache evictions after the surrounding database transaction commits. */
public class CacheEvictor {
    private final CacheTemplate cache;
    private final CacheProperties properties;
    private final ApplicationEventPublisher events;

    public CacheEvictor(CacheTemplate cache, CacheProperties properties,
                        ApplicationEventPublisher events) {
        this.cache = cache;
        this.properties = properties;
        this.events = events;
    }

    public void publish(CacheEvictionSource source) {
        CacheEviction eviction = source.asEviction();
        if (eviction.keys().isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            events.publishEvent(eviction);
        } else {
            evict(eviction);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evictAfterCommit(CacheEviction eviction) {
        evict(eviction);
    }

    private void evict(CacheEviction eviction) {
        CacheSpec spec = properties.spec(eviction.specName());
        for (String key : eviction.keys()) {
            cache.evict(spec, key, eviction.version());
        }
    }
}
