package com.roseboard.infrastructure.cache;

import com.roseboard.infrastructure.cache.store.CacheStoreType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheAutoConfigurationTest {

    @Test
    void defaultsMatchThingsBoardCacheSpecs() {
        CacheProperties properties = new CacheProperties();

        assertThat(properties.backend()).isEqualTo(CacheStoreType.CAFFEINE);
        assertThat(properties.spec("deviceCredentials")).isEqualTo(
                new CacheSpec("deviceCredentials", true, Duration.ofMinutes(1440), 10_000));
        assertThat(properties.spec("devices")).isEqualTo(
                new CacheSpec("devices", true, Duration.ofMinutes(1440), 10_000));
        assertThat(properties.spec("deviceProfiles")).isEqualTo(
                new CacheSpec("deviceProfiles", true, Duration.ofMinutes(1440), 10_000));
        assertThat(properties.spec("attributes")).isEqualTo(
                new CacheSpec("attributes", true, Duration.ofMinutes(1440), 100_000));
        assertThat(properties.spec("tsLatest")).isEqualTo(
                new CacheSpec("tsLatest", true, Duration.ofMinutes(1440), 100_000));
    }

    @Test
    void zeroMaxSizeDisablesOneCacheWithoutDisablingOthers() {
        CacheProperties properties = new CacheProperties();
        properties.specs().get("devices").setMaxSize(0);

        assertThat(properties.spec("devices").enabled()).isFalse();
        assertThat(properties.spec("attributes").enabled()).isTrue();
    }
}
