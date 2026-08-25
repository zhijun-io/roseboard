package com.roseboard.query;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.attribute.DeviceAttribute;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.query.EntityDataKeys;
import com.roseboard.device.query.EntityDataSnapshot;
import com.roseboard.device.query.EntityQueryService;
import com.roseboard.device.telemetry.TelemetryLatest;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import com.roseboard.device.query.filter.SingleEntityFilter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityQueryServiceTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();

    @Mock DeviceMapper deviceMapper;
    @Mock TelemetryService telemetryService;
    @Mock DeviceAttributeService attributeService;
    @InjectMocks
    EntityQueryService service;

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, DeviceMapper.class.getName()),
                DeviceEntity.class);
    }

    @Test
    void countDevicesAppliesTenantTypeAndNamePrefix() {
        DeviceTypeFilter filter = new DeviceTypeFilter(List.of("default"), "sensor-");
        when(deviceMapper.selectCount(any())).thenReturn(3L);

        long count = service.countDevices(TENANT_ID, null, filter);

        assertEquals(3L, count);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(deviceMapper).selectCount(captor.capture());
    }

    @Test
    void resolveDeviceIdRequiresDeviceInTenant() {
        DeviceEntity device = new DeviceEntity();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);

        UUID resolved = service.resolveDeviceId(TENANT_ID,
                new SingleEntityFilter("DEVICE", DEVICE_ID.toString()));

        assertEquals(DEVICE_ID, resolved);
    }

    @Test
    void resolveDeviceIdRejectsCrossTenantDevice() {
        DeviceEntity device = new DeviceEntity();
        device.setId(DEVICE_ID);
        device.setTenantId(UUID.randomUUID());
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);

        assertThrows(IllegalArgumentException.class, () -> service.resolveDeviceId(TENANT_ID,
                new SingleEntityFilter("DEVICE", DEVICE_ID.toString())));
    }

    @Test
    void loadEntityDataReturnsLatestTelemetryAndAttributes() {
        DeviceEntity device = new DeviceEntity();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        when(telemetryService.latest(TENANT_ID, DEVICE_ID, "temperature"))
                .thenReturn(new TelemetryLatest(TENANT_ID, DEVICE_ID, "temperature", 26.5, 1000L, UUID.randomUUID()));
        when(attributeService.find(TENANT_ID, DEVICE_ID, AttributeScope.CLIENT, new AttributeKey("mode")))
                .thenReturn(new DeviceAttribute(TENANT_ID, DEVICE_ID, AttributeScope.CLIENT,
                        new AttributeKey("mode"), new AttributeValue("auto"), 1L, 1000L));

        EntityDataSnapshot snapshot = service.loadEntityData(TENANT_ID, DEVICE_ID, new EntityDataKeys(
                List.of("temperature"),
                List.of("mode"),
                List.of(),
                List.of()));

        assertEquals(DEVICE_ID, snapshot.deviceId());
        assertEquals(26.5, snapshot.latestTelemetry().get("temperature").value());
        assertEquals(1000L, snapshot.latestTelemetry().get("temperature").timestampMs());
        assertEquals("auto", snapshot.clientAttributes().get("mode"));
        verify(attributeService).find(eq(TENANT_ID), eq(DEVICE_ID), eq(AttributeScope.CLIENT), eq(new AttributeKey("mode")));
    }
}
