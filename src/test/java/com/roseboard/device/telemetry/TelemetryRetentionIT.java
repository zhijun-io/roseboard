package com.roseboard.device.telemetry;

import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class TelemetryRetentionIT {
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
        registry.add("roseboard.telemetry.retention-ms", () -> "86400000");
        registry.add("roseboard.telemetry.cleanup-batch-size", () -> "100");
    }

    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceTelemetryStore telemetryStore;
    @Autowired TelemetryRetentionService retentionService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("telemetry-retention-profile-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);

        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);

        DeviceProfileEntity profile = profileService.ensureDefaultForTenant(tenantId);
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setCreatedTime(System.currentTimeMillis());
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profile.getId());
        device.setName("telemetry-retention-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);
    }

    @Test
    void cleanupRemovesOnlyExpiredHistoryAndKeepsLatest() {
        Instant expired = Instant.now().minusSeconds(3 * 86_400);
        Instant current = Instant.now().minusSeconds(3_600);
        UUID expiredWrite = UUID.randomUUID();
        UUID currentWrite = UUID.randomUUID();

        telemetryStore.save(tenantId, deviceId, "temp", 1, expired, expiredWrite);
        telemetryStore.save(tenantId, deviceId, "temp", 2, current, currentWrite);

        int deleted = retentionService.cleanupExpired(Instant.now());

        assertEquals(1, deleted);
        assertEquals(0, telemetryStore.history(
                tenantId, deviceId, "temp", expired.toEpochMilli() - 1, expired.toEpochMilli() + 1, 10).size());
        assertEquals(1, telemetryStore.history(
                tenantId, deviceId, "temp", current.toEpochMilli() - 1, current.toEpochMilli() + 1, 10).size());

        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "temp");
        assertNotNull(latest);
        assertEquals(currentWrite, latest.writeId());
        assertEquals(2, ((Number) latest.value()).intValue());
    }

    @Test
    void cleanupPreservesLatestEvenWhenOnlyExpiredHistoryExisted() {
        Instant expired = Instant.now().minusSeconds(5 * 86_400);
        UUID writeId = UUID.randomUUID();
        telemetryStore.save(tenantId, deviceId, "solo", 7, expired, writeId);

        int deleted = retentionService.cleanupExpired(Instant.now());

        assertEquals(1, deleted);
        assertEquals(0, telemetryStore.history(
                tenantId, deviceId, "solo", expired.toEpochMilli() - 1, Instant.now().toEpochMilli(), 10).size());
        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "solo");
        assertNotNull(latest);
        assertEquals(7, ((Number) latest.value()).intValue());
        assertEquals(writeId, latest.writeId());
    }
}
