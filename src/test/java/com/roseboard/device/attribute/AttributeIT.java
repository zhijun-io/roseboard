package com.roseboard.device.attribute;

import com.roseboard.user.UserAuthority;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class AttributeIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceAttributeStore store;
    @Autowired DeviceAttributeService attributeService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("attribute-profile-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);
        TenantEntity other = new TenantEntity();
        other.setId(otherTenantId);
        other.setCreatedTime(System.currentTimeMillis());
        other.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(other);

        DeviceProfileEntity profile = profileService.ensureDefaultForTenant(tenantId);
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setCreatedTime(System.currentTimeMillis());
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profile.getId());
        device.setName("attribute-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);
    }

    @Test
    void savesAndReadsBoundedAttribute() {
        DeviceAttribute saved = store.save(tenantId, deviceId, AttributeScope.SHARED,
                new AttributeKey("enabled"), new AttributeValue(Map.of("value", true)));

        DeviceAttribute loaded = store.find(tenantId, deviceId, AttributeScope.SHARED,
                new AttributeKey("enabled"));

        assertEquals(saved.version(), loaded.version());
        assertEquals(true, ((Map<?, ?>) loaded.value().value()).get("value"));
    }

    @Test
    void rejectsDeviceFromAnotherTenantBeforeWrite() {
        assertThrows(IllegalArgumentException.class, () -> store.save(
                otherTenantId, deviceId, AttributeScope.SERVER,
                new AttributeKey("secret"), new AttributeValue("value")));
    }

    @Test
    void staleExpectedVersionCannotUpdateOrDelete() {
        AttributeKey key = new AttributeKey("threshold");
        DeviceAttribute saved = store.save(tenantId, deviceId, AttributeScope.SERVER,
                key, new AttributeValue(1L));

        DeviceAttribute updated = store.update(tenantId, deviceId, AttributeScope.SERVER,
                key, new AttributeValue(2L), saved.version());

        assertThrows(IllegalStateException.class, () -> store.update(
                tenantId, deviceId, AttributeScope.SERVER, key, new AttributeValue(3L), saved.version()));
        assertThrows(IllegalStateException.class, () -> store.delete(
                tenantId, deviceId, AttributeScope.SERVER, key, saved.version()));
        assertEquals(2L, ((Number) updated.value().value()).longValue());
    }

    @Test
    void atomicBatchRollsBackEarlierItemsWhenOneItemFails() {
        AttributeKey existingKey = new AttributeKey("existing");
        store.save(tenantId, deviceId, AttributeScope.SERVER, existingKey,
                new AttributeValue("original"));

        AttributeKey newKey = new AttributeKey("new");
        assertThrows(IllegalStateException.class, () -> attributeService.writeBatch(
                server(), tenantId, deviceId, List.of(
                        new AttributeWrite(AttributeScope.SERVER, newKey,
                                new AttributeValue("must rollback"), null),
                        new AttributeWrite(AttributeScope.SERVER, existingKey,
                                new AttributeValue("duplicate"), null)),
                AttributeBatchMode.ATOMIC));

        assertNull(store.find(tenantId, deviceId, AttributeScope.SERVER, newKey));
        assertEquals("original", store.find(tenantId, deviceId, AttributeScope.SERVER, existingKey)
                .value().value());
    }

    private static UsernamePasswordAuthenticationToken server() {
        return new UsernamePasswordAuthenticationToken("server", "n/a",
                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())));
    }
}
