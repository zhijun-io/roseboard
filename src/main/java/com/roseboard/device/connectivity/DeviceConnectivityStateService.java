package com.roseboard.device.connectivity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.roseboard.common.JacksonUtils;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeEntity;
import com.roseboard.device.attribute.DeviceAttributeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceConnectivityStateService {
    public static final String ACTIVE = "active";
    public static final String LAST_CONNECT_TIME = "lastConnectTime";
    public static final String LAST_DISCONNECT_TIME = "lastDisconnectTime";
    public static final String LAST_ACTIVITY_TIME = "lastActivityTime";

    private final DeviceAttributeMapper mapper;

    public DeviceConnectivityStateService(DeviceAttributeMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void onConnect(UUID tenantId, UUID deviceId, long timestamp) {
        write(tenantId, deviceId, ACTIVE, true, timestamp);
        write(tenantId, deviceId, LAST_CONNECT_TIME, timestamp, timestamp);
    }

    @Transactional
    public void onDisconnect(UUID tenantId, UUID deviceId, long timestamp) {
        write(tenantId, deviceId, ACTIVE, false, timestamp);
        write(tenantId, deviceId, LAST_DISCONNECT_TIME, timestamp, timestamp);
    }

    @Transactional
    public void onActivity(UUID tenantId, UUID deviceId, long timestamp) {
        write(tenantId, deviceId, LAST_ACTIVITY_TIME, timestamp, timestamp);
    }

    public DeviceConnectivityState find(UUID tenantId, UUID deviceId) {
        Map<String, JsonNode> values = new HashMap<>();
        mapper.selectList(new QueryWrapper<DeviceAttributeEntity>()
                        .eq("tenant_id", tenantId)
                        .eq("device_id", deviceId)
                        .eq("scope", AttributeScope.SERVER)
                        .in("attribute_key",
                                ACTIVE, LAST_CONNECT_TIME, LAST_DISCONNECT_TIME, LAST_ACTIVITY_TIME))
                .forEach(attribute -> values.put(attribute.getAttributeKey(), attribute.getValue()));
        return new DeviceConnectivityState(
                value(values, ACTIVE, false),
                value(values, LAST_CONNECT_TIME, 0L),
                value(values, LAST_DISCONNECT_TIME, 0L),
                value(values, LAST_ACTIVITY_TIME, 0L));
    }

    private void write(UUID tenantId, UUID deviceId, String key, Object value, long timestamp) {
        QueryWrapper<DeviceAttributeEntity> query = new QueryWrapper<DeviceAttributeEntity>()
                .eq("tenant_id", tenantId)
                .eq("device_id", deviceId)
                .eq("scope", AttributeScope.SERVER)
                .eq("attribute_key", key);
        DeviceAttributeEntity current = mapper.selectOne(query);
        if (current == null) {
            DeviceAttributeEntity created = new DeviceAttributeEntity();
            created.setId(UUID.randomUUID());
            created.setTenantId(tenantId);
            created.setDeviceId(deviceId);
            created.setScope(AttributeScope.SERVER);
            created.setAttributeKey(key);
            created.setValue(JacksonUtils.objectMapper().valueToTree(value));
            created.setVersion(1L);
            created.setUpdatedTime(timestamp);
            mapper.insert(created);
            return;
        }
        current.setValue(JacksonUtils.objectMapper().valueToTree(value));
        current.setVersion(current.getVersion() == null ? 1L : current.getVersion() + 1);
        current.setUpdatedTime(timestamp);
        mapper.updateById(current);
    }

    private static boolean value(Map<String, JsonNode> values, String key, boolean fallback) {
        JsonNode value = values.get(key);
        return value == null ? fallback : value.asBoolean(fallback);
    }

    private static long value(Map<String, JsonNode> values, String key, long fallback) {
        JsonNode value = values.get(key);
        return value == null ? fallback : value.asLong(fallback);
    }
}
