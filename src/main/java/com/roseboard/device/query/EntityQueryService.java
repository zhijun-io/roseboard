package com.roseboard.device.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttribute;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.telemetry.TelemetryLatest;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import com.roseboard.device.query.filter.SingleEntityFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EntityQueryService {
    private final DeviceMapper deviceMapper;
    private final TelemetryService telemetryService;
    private final DeviceAttributeService attributeService;

    public EntityQueryService(DeviceMapper deviceMapper,
                                TelemetryService telemetryService,
                                DeviceAttributeService attributeService) {
        this.deviceMapper = deviceMapper;
        this.telemetryService = telemetryService;
        this.attributeService = attributeService;
    }

    public long countDevices(UUID tenantId, UUID customerId, DeviceTypeFilter filter) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (filter == null) {
            throw new IllegalArgumentException("filter is required");
        }
        return deviceMapper.selectCount(deviceTypeQuery(tenantId, customerId, filter));
    }

    public UUID resolveDeviceId(UUID tenantId, SingleEntityFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter is required");
        }
        if (!"DEVICE".equalsIgnoreCase(filter.getEntityType())) {
            throw new IllegalArgumentException("Unsupported entity type: " + filter.getEntityType());
        }
        if (!StringUtils.hasText(filter.getEntityId())) {
            throw new IllegalArgumentException("entityId is required");
        }
        UUID deviceId = UUID.fromString(filter.getEntityId());
        requireDeviceInTenant(tenantId, deviceId);
        return deviceId;
    }

    public EntityDataSnapshot loadEntityData(UUID tenantId, UUID deviceId, EntityDataKeys keys) {
        if (keys == null) {
            throw new IllegalArgumentException("keys is required");
        }
        requireDeviceInTenant(tenantId, deviceId);

        Map<String, EntityTelemetryPoint> telemetry = new LinkedHashMap<>();
        for (String key : keys.telemetryKeys()) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            TelemetryLatest latest = telemetryService.latest(tenantId, deviceId, key);
            if (latest != null) {
                telemetry.put(key, new EntityTelemetryPoint(latest.timestampMs(), latest.value()));
            }
        }

        return new EntityDataSnapshot(
                deviceId,
                telemetry,
                loadAttributes(tenantId, deviceId, AttributeScope.CLIENT, keys.clientAttributeKeys()),
                loadAttributes(tenantId, deviceId, AttributeScope.SERVER, keys.serverAttributeKeys()),
                loadAttributes(tenantId, deviceId, AttributeScope.SHARED, keys.sharedAttributeKeys()));
    }

    private Map<String, Object> loadAttributes(UUID tenantId, UUID deviceId,
                                              AttributeScope scope, List<String> keys) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return values;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            DeviceAttribute attribute = attributeService.find(tenantId, deviceId, scope, new AttributeKey(key));
            if (attribute != null) {
                values.put(key, attribute.value().value());
            }
        }
        return values;
    }

    private void requireDeviceInTenant(UUID tenantId, UUID deviceId) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new IllegalArgumentException("Device not found in tenant scope");
        }
    }

    private static LambdaQueryWrapper<DeviceEntity> deviceTypeQuery(UUID tenantId, UUID customerId,
                                                                    DeviceTypeFilter filter) {
        LambdaQueryWrapper<DeviceEntity> query = new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getTenantId, tenantId);
        if (customerId != null) {
            query.eq(DeviceEntity::getCustomerId, customerId);
        }
        List<String> deviceTypes = filter.getDeviceTypes();
        if (!deviceTypes.isEmpty()) {
            query.in(DeviceEntity::getType, deviceTypes);
        }
        if (StringUtils.hasText(filter.getDeviceNameFilter())) {
            query.likeRight(DeviceEntity::getName, filter.getDeviceNameFilter());
        }
        return query;
    }

    public boolean deviceMatchesFilter(DeviceTypeFilter filter, String deviceType, String deviceName) {
        if (filter == null) {
            return false;
        }
        List<String> deviceTypes = filter.getDeviceTypes();
        if (!deviceTypes.isEmpty() && (deviceType == null || !deviceTypes.contains(deviceType))) {
            return false;
        }
        if (StringUtils.hasText(filter.getDeviceNameFilter())) {
            return deviceName != null && deviceName.startsWith(filter.getDeviceNameFilter());
        }
        return true;
    }
}
