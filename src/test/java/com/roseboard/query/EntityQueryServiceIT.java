package com.roseboard.query;

import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.query.EntityDataKeys;
import com.roseboard.device.query.EntityDataSnapshot;
import com.roseboard.device.query.EntityQueryService;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.device.telemetry.TelemetryWrite;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import com.roseboard.device.query.filter.SingleEntityFilter;
import com.roseboard.support.IntegrationTestBase;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EntityQueryServiceIT extends IntegrationTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
    }

    @Autowired
    EntityQueryService entityQueryService;
    @Autowired TelemetryService telemetryService;
    @Autowired DeviceAttributeService attributeService;

    private UUID deviceId;

    @BeforeEach
    void seed() {
        seedTenant("entity-query-tp-");
        deviceId = createDevice("entity-query-sensor-alpha");
    }

    @Test
    void countDevicesMatchesCreatedDeviceByTypeAndNamePrefix() {
        DeviceTypeFilter filter = new DeviceTypeFilter(List.of("default"), "entity-query-sensor");

        long count = entityQueryService.countDevices(tenantId, null, filter);

        assertEquals(1L, count);
    }

    @Test
    void loadEntityDataReadsPersistedTelemetryAndAttributes() {
        UUID writeId = UUID.randomUUID();
        telemetryService.saveBatch(tenantId, deviceId, List.of(
                new TelemetryWrite("pressure", 42, Instant.now(), writeId)));
        attributeService.save(tenantId, deviceId, AttributeScope.CLIENT,
                new AttributeKey("mode"), new AttributeValue("manual"));

        EntityDataSnapshot snapshot = entityQueryService.loadEntityData(tenantId, deviceId, new EntityDataKeys(
                List.of("pressure"),
                List.of("mode"),
                List.of(),
                List.of()));

        assertEquals(deviceId, snapshot.deviceId());
        assertEquals(42, snapshot.latestTelemetry().get("pressure").value());
        assertEquals("manual", snapshot.clientAttributes().get("mode"));
    }

    @Test
    void resolveDeviceIdReturnsDeviceForSingleEntityFilter() {
        UUID resolved = entityQueryService.resolveDeviceId(tenantId,
                new SingleEntityFilter("DEVICE", deviceId.toString()));

        assertEquals(deviceId, resolved);
    }

    @Test
    void countDevicesReturnsZeroForNonMatchingPrefix() {
        long count = entityQueryService.countDevices(tenantId, null,
                new DeviceTypeFilter(List.of("default"), "no-such-prefix"));

        assertEquals(0L, count);
    }
}
