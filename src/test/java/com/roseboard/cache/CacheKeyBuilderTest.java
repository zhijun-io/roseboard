package com.roseboard.cache;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyBuilderTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Test
    void attributeKeysSeparateTenantDeviceScopeAndAttribute() {
        String base = CacheKeyBuilder.attribute(TENANT_A, DEVICE, "CLIENT", "firmware");

        assertThat(base).isNotEqualTo(CacheKeyBuilder.attribute(TENANT_B, DEVICE, "CLIENT", "firmware"));
        assertThat(base).isNotEqualTo(CacheKeyBuilder.attribute(TENANT_A, UUID.randomUUID(), "CLIENT", "firmware"));
        assertThat(base).isNotEqualTo(CacheKeyBuilder.attribute(TENANT_A, DEVICE, "SHARED", "firmware"));
        assertThat(base).isNotEqualTo(CacheKeyBuilder.attribute(TENANT_A, DEVICE, "CLIENT", "model"));
    }

    @Test
    void credentialKeysDoNotExposeLookupMaterial() {
        String key = CacheKeyBuilder.credentialsAuth("ACCESS_TOKEN", "secret-token");

        assertThat(key).contains("credentials-auth:ACCESS_TOKEN:");
        assertThat(key).doesNotContain("secret-token");
    }
}
