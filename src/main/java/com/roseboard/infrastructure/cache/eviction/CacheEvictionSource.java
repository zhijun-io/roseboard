package com.roseboard.infrastructure.cache.eviction;

/** Typed or direct cache eviction events routed through {@link CacheEvictor}. */
public interface CacheEvictionSource {
    CacheEviction asEviction();
}
