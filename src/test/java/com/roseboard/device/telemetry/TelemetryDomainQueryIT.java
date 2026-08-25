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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class TelemetryDomainQueryIT {
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

    private final UUID tenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private long baseTs;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("telemetry-domain-profile-" + tenantProfileId);
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
        device.setName("telemetry-domain-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);

        baseTs = (Instant.now().minusSeconds(3_600).toEpochMilli() / 10_000) * 10_000;
        save("temp", 10, baseTs);
        save("temp", 20, baseTs + 1_000);
        save("temp", 30, baseTs + 2_000);
        save("humidity", 40, baseTs + 1_500);
    }

    @Test
    void latestAndKeysSupportMultipleKeys() {
        List<TelemetryLatest> latest = telemetryStore.latest(tenantId, deviceId, List.of("temp", "humidity", "missing"));
        assertEquals(2, latest.size());
        assertEquals(Set.of("temp", "humidity"),
                latest.stream().map(TelemetryLatest::key).collect(Collectors.toSet()));

        List<TelemetryLatest> all = telemetryStore.latestAll(tenantId, deviceId);
        assertEquals(2, all.size());
        assertEquals(List.of("humidity", "temp"), telemetryStore.keys(tenantId, deviceId));
    }

    @Test
    void multiKeyHistoryRespectsOrderAndLimit() {
        List<TelemetryPoint> desc = telemetryStore.history(
                tenantId, deviceId, List.of("temp", "humidity"),
                baseTs, baseTs + 3_000, 10, TelemetryOrder.DESC);
        assertEquals(4, desc.size());
        assertTrue(desc.get(0).timestampMs() >= desc.get(1).timestampMs());

        List<TelemetryPoint> ascLimited = telemetryStore.history(
                tenantId, deviceId, List.of("temp"),
                baseTs, baseTs + 3_000, 2, TelemetryOrder.ASC);
        assertEquals(2, ascLimited.size());
        assertEquals(10, ((Number) ascLimited.get(0).value()).intValue());
        assertEquals(20, ((Number) ascLimited.get(1).value()).intValue());
    }

    @Test
    void aggregateProducesMillisecondBuckets() {
        List<TelemetryBucket> buckets = telemetryStore.aggregate(
                tenantId, deviceId, "temp", baseTs, baseTs + 2_999,
                TelemetryAggregation.AVG, 1_000);
        assertEquals(3, buckets.size());
        assertEquals(10.0, buckets.get(0).value());
        assertEquals(20.0, buckets.get(1).value());
        assertEquals(30.0, buckets.get(2).value());

        List<TelemetryBucket> counts = telemetryStore.aggregate(
                tenantId, deviceId, "temp", baseTs, baseTs + 2_999,
                TelemetryAggregation.COUNT, 10_000);
        assertEquals(1, counts.size());
        assertEquals(3.0, counts.getFirst().value());
    }

    @Test
    void rejectsTooManyAggregationBuckets() {
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.aggregate(tenantId, deviceId, "temp",
                        baseTs, baseTs + 1_000_000, TelemetryAggregation.AVG, 1));
    }

    @Test
    void removeDeletesHistoryAndCanRebuildLatest() {
        int deleted = telemetryStore.remove(tenantId, deviceId, "temp",
                baseTs + 1_500, baseTs + 3_000, true);
        assertEquals(1, deleted);

        List<TelemetryPoint> remaining = telemetryStore.history(
                tenantId, deviceId, "temp", baseTs, baseTs + 3_000, 10);
        assertEquals(2, remaining.size());

        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "temp");
        assertEquals(20, ((Number) latest.value()).intValue());
        assertEquals(baseTs + 1_000, latest.timestampMs());
    }

    @Test
    void removeAllHistoryClearsLatestWhenRewriteEnabled() {
        telemetryStore.remove(tenantId, deviceId, "humidity",
                baseTs, baseTs + 3_000, true);
        assertNull(telemetryStore.latest(tenantId, deviceId, "humidity"));
        assertTrue(telemetryStore.history(tenantId, deviceId, "humidity",
                baseTs, baseTs + 3_000, 10).isEmpty());
    }

    private void save(String key, Object value, long timestampMs) {
        telemetryStore.save(tenantId, deviceId, key, value,
                Instant.ofEpochMilli(timestampMs), UUID.randomUUID());
    }
}
