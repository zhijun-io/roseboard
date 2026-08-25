package com.roseboard.device.connectivity;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeEntity;
import com.roseboard.device.attribute.DeviceAttributeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TDeviceConnectivityStateServiceTest {
    @Mock
    private DeviceAttributeMapper mapper;

    @Test
    void storesAndReadsConnectivityState() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(mapper.selectOne(any())).thenReturn(null);

        DeviceConnectivityStateService service = new DeviceConnectivityStateService(mapper);
        service.onConnect(tenantId, deviceId, 100L);
        service.onActivity(tenantId, deviceId, 110L);
        service.onDisconnect(tenantId, deviceId, 120L);

        verify(mapper, org.mockito.Mockito.times(5)).insert(any(DeviceAttributeEntity.class));

        when(mapper.selectList(any())).thenReturn(List.of(
                attribute(DeviceConnectivityStateService.ACTIVE, false),
                attribute(DeviceConnectivityStateService.LAST_CONNECT_TIME, 100L),
                attribute(DeviceConnectivityStateService.LAST_DISCONNECT_TIME, 120L),
                attribute(DeviceConnectivityStateService.LAST_ACTIVITY_TIME, 110L)));

        DeviceConnectivityState state = service.find(tenantId, deviceId);
        assertFalse(state.active());
        assertEquals(100L, state.lastConnectTime());
        assertEquals(120L, state.lastDisconnectTime());
        assertEquals(110L, state.lastActivityTime());
    }

    @Test
    void updatesExistingAttribute() {
        DeviceAttributeEntity current = attribute(DeviceConnectivityStateService.ACTIVE, false);
        current.setId(UUID.randomUUID());
        current.setVersion(3L);
        when(mapper.selectOne(any())).thenReturn(current, null);

        new DeviceConnectivityStateService(mapper)
                .onConnect(UUID.randomUUID(), UUID.randomUUID(), 100L);

        verify(mapper).updateById(current);
        assertTrue(current.getValue().asBoolean());
        assertEquals(4L, current.getVersion());
    }

    private static DeviceAttributeEntity attribute(String key, Object value) {
        DeviceAttributeEntity attribute = new DeviceAttributeEntity();
        attribute.setAttributeKey(key);
        attribute.setScope(AttributeScope.SERVER);
        attribute.setValue(JacksonUtils.objectMapper().valueToTree(value));
        return attribute;
    }
}
