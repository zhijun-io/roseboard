package com.roseboard.device.telemetry;

import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class TelemetryQueryIT {
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
    @Autowired DeviceTelemetryStore telemetryStore;
    @MockitoSpyBean TelemetryLatestMapper latestMapper;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        Mockito.reset(latestMapper);
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("telemetry-query-profile-" + tenantProfileId);
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
        device.setName("telemetry-query-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);
    }

    @Test
    void latePointDoesNotOverwriteNewerLatest() {
        Instant newer = Instant.now().minusSeconds(10);
        Instant older = Instant.now().minusSeconds(60);
        UUID newerWrite = UUID.randomUUID();
        UUID olderWrite = UUID.randomUUID();

        telemetryStore.save(tenantId, deviceId, "temp", 30, newer, newerWrite);
        telemetryStore.save(tenantId, deviceId, "temp", 10, older, olderWrite);

        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "temp");
        List<TelemetryPoint> history = telemetryStore.history(
                tenantId, deviceId, "temp", older.toEpochMilli(), newer.toEpochMilli(), 10);

        assertEquals(2, history.size());
        assertEquals(newerWrite, latest.writeId());
        assertEquals(30, ((Number) latest.value()).intValue());
    }

    @Test
    void sameTimestampUsesWriteIdTieBreak() {
        Instant ts = Instant.now().minusSeconds(15);
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        telemetryStore.save(tenantId, deviceId, "pressure", 1, ts, low);
        telemetryStore.save(tenantId, deviceId, "pressure", 2, ts, high);

        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "pressure");
        assertEquals(high, latest.writeId());
        assertEquals(2, ((Number) latest.value()).intValue());
    }

    @Test
    void rejectsInvalidHistoryRangeBeforeRead() {
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.history(tenantId, deviceId, "temp", 10, 5, 10));
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.history(tenantId, deviceId, "temp", 1, 2, 0));
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.history(tenantId, deviceId, "temp", 1, 2, 501));
    }

    @Test
    void rejectsCrossTenantQuery() {
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.latest(otherTenantId, deviceId, "temp"));
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.history(otherTenantId, deviceId, "temp", 0, 1, 10));
    }

    @Test
    void latestFailureRollsBackHistoryInsert() {
        Instant ts = Instant.now().minusSeconds(3);
        UUID writeId = UUID.randomUUID();
        doThrow(new IllegalStateException("forced latest failure"))
                .when(latestMapper)
                .upsert(eq(tenantId), eq(deviceId), eq("rollback"), any(),
                        anyLong(), eq(writeId), anyLong());

        assertThrows(IllegalStateException.class, () ->
                telemetryStore.save(tenantId, deviceId, "rollback", 99, ts, writeId));

        Mockito.reset(latestMapper);
        assertNull(telemetryStore.latest(tenantId, deviceId, "rollback"));
        assertEquals(0, telemetryStore.history(
                tenantId, deviceId, "rollback", ts.toEpochMilli() - 1, ts.toEpochMilli() + 1, 10).size());
    }
}
