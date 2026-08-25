package com.roseboard.infrastructure.websocket.cmd.v2;

import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.infrastructure.websocket.SubscriptionErrorCode;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;
import com.roseboard.infrastructure.websocket.cmd.v2.query.DeviceTypeWireFilter;
import com.roseboard.infrastructure.websocket.cmd.v2.query.EntityFilter;
import com.roseboard.infrastructure.websocket.cmd.v2.query.SingleEntityWireFilter;
import com.roseboard.device.query.EntityDataKeys;
import com.roseboard.device.query.EntityDataSnapshot;
import com.roseboard.device.query.EntityTelemetryPoint;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import com.roseboard.device.query.filter.SingleEntityFilter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EntityDataMapper {
    private EntityDataMapper() {
    }

    public static SingleEntityFilter toSingleEntityFilter(EntityFilterQuery query) {
        EntityFilter filter = requireEntityFilter(query);
        if (filter instanceof SingleEntityWireFilter singleEntity) {
            EntityIdRef entityId = requireDeviceEntityId(singleEntity.getSingleEntity());
            return new SingleEntityFilter(entityId.getEntityType(), entityId.getId());
        }
        if (filter instanceof DeviceTypeWireFilter) {
            throw new IllegalArgumentException("deviceType filter is not supported for ENTITY_DATA");
        }
        throw new IllegalArgumentException("Unsupported entity filter type");
    }

    public static DeviceTypeFilter toDeviceTypeFilter(EntityFilterQuery query) {
        EntityFilter filter = requireEntityFilter(query);
        if (filter instanceof DeviceTypeWireFilter deviceType) {
            return new DeviceTypeFilter(deviceType.getDeviceTypes(), deviceType.getDeviceNameFilter());
        }
        if (filter instanceof SingleEntityWireFilter) {
            throw new IllegalArgumentException("singleEntity filter is not supported for ENTITY_COUNT");
        }
        throw new IllegalArgumentException("Unsupported entity filter type");
    }

    public static EntityDataKeys toEntityDataKeys(List<EntityKey> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("latestCmd.keys is required");
        }
        List<String> telemetryKeys = new ArrayList<>();
        List<String> clientAttributeKeys = new ArrayList<>();
        List<String> serverAttributeKeys = new ArrayList<>();
        List<String> sharedAttributeKeys = new ArrayList<>();
        for (EntityKey key : keys) {
            if (key == null || key.getType() == null || key.getKey() == null) {
                continue;
            }
            switch (key.getType()) {
                case TIME_SERIES -> telemetryKeys.add(key.getKey());
                case CLIENT_ATTRIBUTE, ATTRIBUTE -> clientAttributeKeys.add(key.getKey());
                case SERVER_ATTRIBUTE -> serverAttributeKeys.add(key.getKey());
                case SHARED_ATTRIBUTE -> sharedAttributeKeys.add(key.getKey());
                default -> throw new IllegalArgumentException("Unsupported entity key type: " + key.getType());
            }
        }
        return new EntityDataKeys(telemetryKeys, clientAttributeKeys, serverAttributeKeys, sharedAttributeKeys);
    }

    public static EntityData toEntityData(EntityDataSnapshot snapshot, List<EntityKey> keys) {
        Map<EntityKeyType, Map<String, TsValue>> latest = buildLatest(snapshot, keys);
        return new EntityData(deviceEntityId(snapshot.deviceId()), latest.isEmpty() ? null : latest);
    }

    public static EntityDataUpdate toIncrementalUpdate(int cmdId, UUID deviceId, EntityKeyType type,
                                                       String key, long ts, Object value) {
        return new EntityDataUpdate(cmdId, null, List.of(toIncrementalEntityData(deviceId, type, key, ts, value)));
    }

    public static boolean subscribes(EntityDataKeys keys, EntityKeyType keyType, String key) {
        return switch (keyType) {
            case TIME_SERIES -> keys.hasTelemetryKey(key);
            case CLIENT_ATTRIBUTE, ATTRIBUTE -> keys.hasAttributeKey(AttributeScope.CLIENT, key);
            case SERVER_ATTRIBUTE -> keys.hasAttributeKey(AttributeScope.SERVER, key);
            case SHARED_ATTRIBUTE -> keys.hasAttributeKey(AttributeScope.SHARED, key);
            default -> false;
        };
    }

    public static CmdUpdate errorUpdate(WsCmdType type, int cmdId, SubscriptionErrorCode errorCode, String errorMsg) {
        if (!type.usesCmdUpdate()) {
            throw new IllegalArgumentException("Not a v2 cmd update type: " + type);
        }
        return switch (type) {
            case ENTITY_DATA, ENTITY_DATA_UNSUBSCRIBE -> new EntityDataUpdate(cmdId, errorCode, errorMsg);
            case ENTITY_COUNT, ENTITY_COUNT_UNSUBSCRIBE -> new EntityCountUpdate(cmdId, errorCode, errorMsg);
            case NOTIFICATIONS, NOTIFICATIONS_UNSUBSCRIBE -> new com.roseboard.infrastructure.websocket.cmd.notification.NotificationsUpdate(
                    cmdId, errorCode.getCode(), errorMsg != null ? errorMsg : errorCode.getDefaultMsg());
            case NOTIFICATIONS_COUNT -> new com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountUpdate(
                    cmdId, errorCode.getCode(), errorMsg != null ? errorMsg : errorCode.getDefaultMsg());
            default -> throw new IllegalArgumentException("Not a v2 cmd update type: " + type);
        };
    }

    public static EntityKeyType toEntityKeyType(AttributeScope scope) {
        return switch (scope) {
            case CLIENT -> EntityKeyType.CLIENT_ATTRIBUTE;
            case SERVER -> EntityKeyType.SERVER_ATTRIBUTE;
            case SHARED -> EntityKeyType.SHARED_ATTRIBUTE;
        };
    }

    private static EntityData toIncrementalEntityData(UUID deviceId, EntityKeyType type, String key, long ts, Object value) {
        Map<EntityKeyType, Map<String, TsValue>> latest = new EnumMap<>(EntityKeyType.class);
        latest.put(type, Map.of(key, new TsValue(ts, String.valueOf(value))));
        return new EntityData(deviceEntityId(deviceId), latest);
    }

    private static EntityFilter requireEntityFilter(EntityFilterQuery query) {
        if (query == null || query.getEntityFilter() == null) {
            throw new IllegalArgumentException("query.entityFilter is required");
        }
        return query.getEntityFilter();
    }

    private static EntityIdRef requireDeviceEntityId(EntityIdRef entityId) {
        if (entityId == null || entityId.getEntityType() == null || entityId.getId() == null) {
            throw new IllegalArgumentException("singleEntity.entityType and id are required");
        }
        if (!"DEVICE".equalsIgnoreCase(entityId.getEntityType())) {
            throw new IllegalArgumentException("Unsupported entity type: " + entityId.getEntityType());
        }
        return entityId;
    }

    private static EntityIdRef deviceEntityId(UUID deviceId) {
        return new EntityIdRef("DEVICE", deviceId.toString());
    }

    private static Map<EntityKeyType, Map<String, TsValue>> buildLatest(EntityDataSnapshot snapshot, List<EntityKey> keys) {
        Map<EntityKeyType, Map<String, TsValue>> latest = new EnumMap<>(EntityKeyType.class);
        for (EntityKey key : keys) {
            if (key == null || key.getType() == null || key.getKey() == null) {
                continue;
            }
            TsValue value = resolveValue(snapshot, key);
            if (value == null) {
                continue;
            }
            latest.computeIfAbsent(key.getType(), ignored -> new LinkedHashMap<>())
                    .put(key.getKey(), value);
        }
        return latest;
    }

    private static TsValue resolveValue(EntityDataSnapshot snapshot, EntityKey key) {
        return switch (key.getType()) {
            case TIME_SERIES -> toTsValue(snapshot.latestTelemetry().get(key.getKey()));
            case CLIENT_ATTRIBUTE, ATTRIBUTE -> toAttributeTsValue(snapshot.clientAttributes().get(key.getKey()));
            case SERVER_ATTRIBUTE -> toAttributeTsValue(snapshot.serverAttributes().get(key.getKey()));
            case SHARED_ATTRIBUTE -> toAttributeTsValue(snapshot.sharedAttributes().get(key.getKey()));
            default -> null;
        };
    }

    private static TsValue toTsValue(EntityTelemetryPoint point) {
        if (point == null) {
            return null;
        }
        return new TsValue(point.timestampMs(), String.valueOf(point.value()));
    }

    private static TsValue toAttributeTsValue(Object value) {
        if (value == null) {
            return null;
        }
        return new TsValue(System.currentTimeMillis(), String.valueOf(value));
    }
}
