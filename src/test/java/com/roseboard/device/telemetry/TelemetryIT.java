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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class TelemetryIT {
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
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("telemetry-profile-" + tenantProfileId);
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
        device.setName("telemetry-device-" + deviceId);
        device.setVersion(1L);
        deviceMapper.insert(device);
    }

    @Test
    void sameWriteIdSameContentIsIdempotent() {
        UUID writeId = UUID.randomUUID();
        Instant ts = Instant.now().minusSeconds(10);

        telemetryStore.save(tenantId, deviceId, "temp", 21.5, ts, writeId);
        telemetryStore.save(tenantId, deviceId, "temp", 21.5, ts, writeId);

        List<TelemetryPoint> history = telemetryStore.history(
                tenantId, deviceId, "temp", ts.toEpochMilli() - 1, ts.toEpochMilli() + 1, 10);
        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "temp");

        assertEquals(1, history.size());
        assertNotNull(latest);
        assertEquals(writeId, latest.writeId());
        assertEquals(21.5, ((Number) latest.value()).doubleValue());
    }

    @Test
    void sameWriteIdDifferentContentConflicts() {
        UUID writeId = UUID.randomUUID();
        Instant ts = Instant.now().minusSeconds(20);

        telemetryStore.save(tenantId, deviceId, "humidity", 40, ts, writeId);

        assertThrows(IllegalStateException.class, () ->
                telemetryStore.save(tenantId, deviceId, "humidity", 41, ts, writeId));

        List<TelemetryPoint> history = telemetryStore.history(
                tenantId, deviceId, "humidity", ts.toEpochMilli() - 1, ts.toEpochMilli() + 1, 10);
        assertEquals(1, history.size());
        assertEquals(40, ((Number) history.getFirst().value()).intValue());
    }

    @Test
    void concurrentSameWriteIdSameContentIsIdempotent() throws Exception {
        int workers = 16;
        UUID writeId = UUID.randomUUID();
        Instant ts = Instant.now().minusSeconds(10);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    telemetryStore.save(tenantId, deviceId, "temp", 21.5, ts, writeId);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<TelemetryPoint> history = telemetryStore.history(
                tenantId, deviceId, "temp", ts.toEpochMilli() - 1, ts.toEpochMilli() + 1, 100);
        TelemetryLatest latest = telemetryStore.latest(tenantId, deviceId, "temp");
        assertEquals(1, history.size());
        assertNotNull(latest);
        assertEquals(writeId, latest.writeId());
    }

    @Test
    void rejectsTimestampTooFarInTheFuture() {
        Instant future = Instant.now().plusSeconds(301);
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.save(tenantId, deviceId, "temp", 1, future, UUID.randomUUID()));
    }

    @Test
    void rejectsDeviceFromAnotherTenantBeforeWrite() {
        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.save(otherTenantId, deviceId, "temp", 1, Instant.now(), UUID.randomUUID()));
    }

    @Test
    void malformedBatchDoesNotPartiallyPersist() {
        Instant ts = Instant.now().minusSeconds(5);
        UUID goodWriteId = UUID.randomUUID();
        List<TelemetryWrite> writes = List.of(
                new TelemetryWrite("temp", 10, ts, goodWriteId),
                new TelemetryWrite("", 11, ts, UUID.randomUUID())
        );

        assertThrows(IllegalArgumentException.class, () ->
                telemetryStore.saveBatch(tenantId, deviceId, writes));

        List<TelemetryPoint> history = telemetryStore.history(
                tenantId, deviceId, "temp", ts.toEpochMilli() - 1, ts.toEpochMilli() + 1, 10);
        assertEquals(0, history.size());
        assertEquals(null, telemetryStore.latest(tenantId, deviceId, "temp"));
    }
}
