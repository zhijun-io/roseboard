package com.roseboard.cache;

import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.store.CacheStoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
class CacheClusterCompatibilityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(CacheClusterCompatibilityConfiguration.class);

    CacheClusterCompatibilityConfiguration(
            CacheProperties properties,
            @Value("${roseboard.cluster.enabled:false}") boolean clusterEnabled) {
        if (clusterEnabled && properties.backend() == CacheStoreType.CAFFEINE) {
            log.warn("roseboard.cluster.enabled=true with roseboard.cache.backend=caffeine: "
                    + "cache eviction is local to each JVM; use backend=redis for shared invalidation");
        }
    }
}
