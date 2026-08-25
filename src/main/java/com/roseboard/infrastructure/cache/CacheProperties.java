package com.roseboard.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.roseboard.infrastructure.cache.store.CacheStoreType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "roseboard.cache")
public class CacheProperties {
    private CacheStoreType backend = CacheStoreType.CAFFEINE;
    private final Map<String, CacheSpec> specs = defaults();

    public CacheStoreType backend() {
        return backend;
    }

    public void setBackend(CacheStoreType backend) {
        this.backend = backend;
    }

    public Map<String, CacheSpec> specs() {
        return specs;
    }

    public CacheSpec spec(String name) {
        CacheSpec spec = specs.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown cache spec: " + name);
        }
        return spec;
    }

    private static Map<String, CacheSpec> defaults() {
        Map<String, CacheSpec> defaults = new LinkedHashMap<>();
        Duration ttl = Duration.ofMinutes(1440);
        defaults.put("deviceCredentials", new CacheSpec("deviceCredentials", true, ttl, 10_000));
        defaults.put("devices", new CacheSpec("devices", true, ttl, 10_000));
        defaults.put("deviceProfiles", new CacheSpec("deviceProfiles", true, ttl, 10_000));
        defaults.put("attributes", new CacheSpec("attributes", true, ttl, 100_000));
        defaults.put("tsLatest", new CacheSpec("tsLatest", true, ttl, 100_000));
        return defaults;
    }
}
