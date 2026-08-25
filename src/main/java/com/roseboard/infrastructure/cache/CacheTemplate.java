package com.roseboard.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import com.roseboard.infrastructure.cache.store.CacheStore;
import com.roseboard.infrastructure.cache.store.CacheStoreTransaction;

import java.util.Objects;
import java.util.function.Supplier;

/** Cache-aside reads with verified database fallback. */
public class CacheTemplate {
    private final CacheStore store;
    private final MeterRegistry meterRegistry;

    public CacheTemplate(CacheStore store) {
        this(store, null);
    }

    public CacheTemplate(CacheStore store, MeterRegistry meterRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.meterRegistry = meterRegistry;
    }

    public <T> T get(CacheSpec spec, String key, Supplier<T> loader, CacheCodec<T> codec) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(codec, "codec");
        if (!spec.enabled()) {
            return loader.get();
        }

        CacheRead<T> cached = read(spec, key, codec);
        if (cached.hit()) {
            recordLookup("hit", spec.name());
            return cached.value();
        }
        recordLookup("miss", spec.name());

        CacheStoreTransaction transaction;
        try {
            transaction = store.beginTransaction(spec, key);
        } catch (RuntimeException failure) {
            recordFailure("begin-transaction", spec.name());
            return loader.get();
        }
        T loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException failure) {
            safeRollback(transaction);
            throw failure;
        }
        try {
            Long version = codec.version(loaded);
            if (loaded == null && version == null) {
                version = 0L;
            }
            transaction.put(encode(loaded, codec), version);
            transaction.commit();
        } catch (RuntimeException failure) {
            safeRollback(transaction);
            recordFailure("fill", spec.name());
        }
        return loaded;
    }

    public void evict(CacheSpec spec, String key) {
        evict(spec, key, null);
    }

    public void evict(CacheSpec spec, String key, Long version) {
        if (!spec.enabled()) {
            return;
        }
        try {
            store.evict(spec, key, version);
        } catch (RuntimeException failure) {
            recordFailure("evict", spec.name());
        }
    }

    private <T> CacheRead<T> read(CacheSpec spec, String key, CacheCodec<T> codec) {
        byte[] raw;
        try {
            raw = store.get(spec, key);
        } catch (RuntimeException failure) {
            recordFailure("read", spec.name());
            return CacheRead.miss();
        }
        if (raw == null) {
            return CacheRead.miss();
        }
        try {
            if (CacheEntryEncoding.isNullEntry(raw)) {
                return CacheRead.hit(null);
            }
            return CacheRead.hit(codec.decode(CacheEntryEncoding.decodePayload(raw)));
        } catch (RuntimeException failure) {
            try {
                store.evict(spec, key);
            } catch (RuntimeException ignored) {
                recordFailure("evict-corrupt-entry", spec.name());
            }
            return CacheRead.miss();
        }
    }

    private static <T> byte[] encode(T value, CacheCodec<T> codec) {
        if (value == null) {
            return CacheEntryEncoding.encodeNull();
        }
        return CacheEntryEncoding.encodeValue(codec.encode(value));
    }

    private static void safeRollback(CacheStoreTransaction transaction) {
        try {
            transaction.rollback();
        } catch (RuntimeException ignored) {
            // The loader result remains authoritative.
        }
    }

    private void recordLookup(String result, String specName) {
        increment("roseboard.cache.lookup", "result", result, "spec", specName);
    }

    private void recordFailure(String operation, String specName) {
        increment("roseboard.cache.failure", "operation", operation, "spec", specName);
    }

    private void increment(String name, String tagKey, String tagValue, String specKey, String specName) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(name, tagKey, tagValue, specKey, specName).increment();
    }

    private record CacheRead<T>(boolean hit, T value) {
        static <T> CacheRead<T> miss() {
            return new CacheRead<>(false, null);
        }

        static <T> CacheRead<T> hit(T value) {
            return new CacheRead<>(true, value);
        }
    }
}
