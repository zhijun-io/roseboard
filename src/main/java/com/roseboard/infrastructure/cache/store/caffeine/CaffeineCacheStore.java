package com.roseboard.infrastructure.cache.store.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.store.CacheStore;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class CaffeineCacheStore implements CacheStore {
    private final Map<String, Cache<String, CachedEntry>> caches = new ConcurrentHashMap<>();
    private final ReentrantLock transactionLock = new ReentrantLock();
    private final Map<String, Set<CaffeineCacheStoreTransaction>> transactions = new HashMap<>();
    private final Map<String, Cache<String, Long>> versionFences = new ConcurrentHashMap<>();

    @Override
    public byte[] get(CacheSpec spec, String key) {
        CachedEntry entry = cache(spec).getIfPresent(key);
        return entry == null ? null : entry.value();
    }

    @Override
    public void put(CacheSpec spec, String key, byte[] value) {
        transactionLock.lock();
        try {
            failTransactions(spec, key);
            cache(spec).put(key, new CachedEntry(value, null));
        } finally {
            transactionLock.unlock();
        }
    }

    @Override
    public void evict(CacheSpec spec, String key) {
        evict(spec, key, null);
    }

    @Override
    public void evict(CacheSpec spec, String key, Long version) {
        transactionLock.lock();
        try {
            if (version != null) {
                CachedEntry current = cache(spec).getIfPresent(key);
                if (current != null && current.version() != null && version <= current.version()) {
                    return;
                }
                versionFence(spec).asMap().merge(key, version, Math::max);
            } else {
                versionFence(spec).invalidate(key);
            }
            failTransactions(spec, key);
            cache(spec).invalidate(key);
        } finally {
            transactionLock.unlock();
        }
    }

    @Override
    public CacheStoreTransaction beginTransaction(CacheSpec spec, String key) {
        CaffeineCacheStoreTransaction transaction = new CaffeineCacheStoreTransaction(this, spec, key);
        transactionLock.lock();
        try {
            transactions.computeIfAbsent(transactionKey(spec, key), ignored -> new HashSet<>()).add(transaction);
            return transaction;
        } finally {
            transactionLock.unlock();
        }
    }

    boolean commit(CaffeineCacheStoreTransaction transaction, byte[] value, Long version) {
        transactionLock.lock();
        try {
            String transactionKey = transactionKey(transaction.spec(), transaction.key());
            Set<CaffeineCacheStoreTransaction> active = transactions.get(transactionKey);
            if (active == null || !active.remove(transaction) || transaction.failed()
                    || isBehindFence(transaction.spec(), transaction.key(), version)) {
                transaction.complete();
                removeEmpty(transactionKey, active);
                return false;
            }
            for (CaffeineCacheStoreTransaction other : active) {
                other.fail();
            }
            if (value != null) {
                CachedEntry current = cache(transaction.spec()).getIfPresent(transaction.key());
                if (current == null
                        || (version != null
                        && (current.version() == null || version > current.version()))) {
                    cache(transaction.spec()).put(transaction.key(), new CachedEntry(value, version));
                }
            }
            removeEmpty(transactionKey, active);
            transaction.complete();
            return true;
        } finally {
            transactionLock.unlock();
        }
    }

    void rollback(CaffeineCacheStoreTransaction transaction) {
        transactionLock.lock();
        try {
            String transactionKey = transactionKey(transaction.spec(), transaction.key());
            Set<CaffeineCacheStoreTransaction> active = transactions.get(transactionKey);
            if (active != null) {
                active.remove(transaction);
                removeEmpty(transactionKey, active);
            }
            transaction.complete();
        } finally {
            transactionLock.unlock();
        }
    }

    private boolean isBehindFence(CacheSpec spec, String key, Long version) {
        Long fence = versionFence(spec).getIfPresent(key);
        return fence != null && (version == null || version <= fence);
    }

    private Cache<String, Long> versionFence(CacheSpec spec) {
        return versionFences.computeIfAbsent(spec.name(), ignored -> Caffeine.newBuilder()
                .maximumSize(Math.max(1, spec.maxSize()))
                .expireAfterWrite(spec.timeToLive().toMillis(), TimeUnit.MILLISECONDS)
                .build());
    }

    private void failTransactions(CacheSpec spec, String key) {
        Set<CaffeineCacheStoreTransaction> active = transactions.get(transactionKey(spec, key));
        if (active != null) {
            active.forEach(CaffeineCacheStoreTransaction::fail);
        }
    }

    private void removeEmpty(String key, Set<CaffeineCacheStoreTransaction> active) {
        if (active != null && active.isEmpty()) {
            transactions.remove(key);
        }
    }

    private static String transactionKey(CacheSpec spec, String key) {
        return spec.name() + '\u0000' + key;
    }

    private Cache<String, CachedEntry> cache(CacheSpec spec) {
        return caches.computeIfAbsent(spec.name(), ignored -> Caffeine.newBuilder()
                .maximumSize(spec.maxSize())
                .expireAfterWrite(spec.timeToLive().toMillis(), TimeUnit.MILLISECONDS)
                .build());
    }

    private record CachedEntry(byte[] value, Long version) {
    }
}
