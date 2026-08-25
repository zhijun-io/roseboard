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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class AttributeQueryIT {
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
        tenantProfile.setName("attr-query-profile-" + tenantProfileId);
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
        device.setName("attr-query-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);

        store.save(tenantId, deviceId, AttributeScope.SERVER,
                new AttributeKey("a"), new AttributeValue(1L));
        store.save(tenantId, deviceId, AttributeScope.SERVER,
                new AttributeKey("b"), new AttributeValue(2L));
        store.save(tenantId, deviceId, AttributeScope.SHARED,
                new AttributeKey("shared"), new AttributeValue(true));
    }

    @Test
    void findAllReturnsOnlyRequestedScope() {
        List<DeviceAttribute> server = store.findAll(tenantId, deviceId, AttributeScope.SERVER);
        assertEquals(2, server.size());
        Set<String> keys = server.stream().map(item -> item.key().value()).collect(Collectors.toSet());
        assertEquals(Set.of("a", "b"), keys);
    }

    @Test
    void findByKeysReturnsOnlyExistingKeys() {
        List<DeviceAttribute> found = store.find(tenantId, deviceId, AttributeScope.SERVER,
                List.of(new AttributeKey("a"), new AttributeKey("missing"), new AttributeKey("b")));
        assertEquals(2, found.size());
        assertEquals(Set.of("a", "b"), found.stream().map(item -> item.key().value()).collect(Collectors.toSet()));
    }

    @Test
    void keysListsAttributeKeysForScope() {
        assertEquals(List.of("a", "b"), store.keys(tenantId, deviceId, AttributeScope.SERVER));
        assertEquals(List.of("shared"), store.keys(tenantId, deviceId, AttributeScope.SHARED));
        assertTrue(store.keys(tenantId, deviceId, AttributeScope.CLIENT).isEmpty());
    }

    @Test
    void rejectsOversizedKeyBatch() {
        List<AttributeKey> keys = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> new AttributeKey("k" + i))
                .toList();
        assertThrows(IllegalArgumentException.class, () ->
                store.find(tenantId, deviceId, AttributeScope.SERVER, keys));
    }

    @Test
    void rejectsCrossTenantQuery() {
        assertThrows(IllegalArgumentException.class, () ->
                store.findAll(otherTenantId, deviceId, AttributeScope.SERVER));
        assertThrows(IllegalArgumentException.class, () ->
                store.keys(otherTenantId, deviceId, AttributeScope.SERVER));
    }

    @Test
    void serviceReadAllEnforcesAuthorization() {
        List<DeviceAttribute> attributes = attributeService.readAll(
                server(), tenantId, deviceId, AttributeScope.SERVER);
        assertEquals(2, attributes.size());

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                attributeService.readAll(device(), tenantId, deviceId, AttributeScope.SERVER));
    }

    private static UsernamePasswordAuthenticationToken server() {
        return new UsernamePasswordAuthenticationToken("server", "n/a",
                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())));
    }

    private static UsernamePasswordAuthenticationToken device() {
        return new UsernamePasswordAuthenticationToken("device", "n/a",
                List.of(new SimpleGrantedAuthority(AttributeAuthorizationService.DEVICE_AUTHENTICATED)));
    }
}
