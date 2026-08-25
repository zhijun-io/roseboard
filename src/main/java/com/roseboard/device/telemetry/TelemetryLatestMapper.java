package com.roseboard.device.telemetry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Mapper
public interface TelemetryLatestMapper extends BaseMapper<TelemetryLatestEntity> {

    @Insert("""
            insert into telemetry_latest
                (tenant_id, device_id, telemetry_key, point_value, timestamp_ms, write_id, updated_time)
            values
                (#{tenantId}, #{deviceId}, #{telemetryKey}, cast(#{pointValueJson} as jsonb),
                 #{timestampMs}, #{writeId}, #{updatedTime})
            on conflict (tenant_id, device_id, telemetry_key) do update
            set point_value = excluded.point_value,
                timestamp_ms = excluded.timestamp_ms,
                write_id = excluded.write_id,
                updated_time = excluded.updated_time
            where telemetry_latest.timestamp_ms < excluded.timestamp_ms
               or (telemetry_latest.timestamp_ms = excluded.timestamp_ms
                   and telemetry_latest.write_id::text < excluded.write_id::text)
            """)
    int upsertAtomic(UUID tenantId, UUID deviceId, String telemetryKey, String pointValueJson,
                     long timestampMs, UUID writeId, long updatedTime);

    default TelemetryLatestEntity find(UUID tenantId, UUID deviceId, String key) {
        return selectOne(new LambdaQueryWrapper<TelemetryLatestEntity>()
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .eq(TelemetryLatestEntity::getTelemetryKey, key));
    }

    default List<TelemetryLatestEntity> findByKeys(UUID tenantId, UUID deviceId, Collection<String> keys) {
        return selectList(new LambdaQueryWrapper<TelemetryLatestEntity>()
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .in(TelemetryLatestEntity::getTelemetryKey, keys)
                .orderByAsc(TelemetryLatestEntity::getTelemetryKey));
    }

    default List<TelemetryLatestEntity> findAll(UUID tenantId, UUID deviceId) {
        return selectList(new LambdaQueryWrapper<TelemetryLatestEntity>()
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .orderByAsc(TelemetryLatestEntity::getTelemetryKey));
    }

    default List<String> keys(UUID tenantId, UUID deviceId) {
        return selectList(new LambdaQueryWrapper<TelemetryLatestEntity>()
                .select(TelemetryLatestEntity::getTelemetryKey)
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .orderByAsc(TelemetryLatestEntity::getTelemetryKey))
                .stream()
                .map(TelemetryLatestEntity::getTelemetryKey)
                .toList();
    }

    default int deleteByKey(UUID tenantId, UUID deviceId, String key) {
        return delete(new LambdaQueryWrapper<TelemetryLatestEntity>()
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .eq(TelemetryLatestEntity::getTelemetryKey, key));
    }

    default void upsert(UUID tenantId, UUID deviceId, String key, JsonNode value,
                        long timestampMs, UUID writeId, long updatedTime) {
        upsertAtomic(tenantId, deviceId, key, value.toString(), timestampMs, writeId, updatedTime);
    }

    default void replace(UUID tenantId, UUID deviceId, String key, JsonNode value,
                         long timestampMs, UUID writeId, long updatedTime) {
        TelemetryLatestEntity existing = find(tenantId, deviceId, key);
        if (existing == null) {
            insert(newEntity(tenantId, deviceId, key, value, timestampMs, writeId, updatedTime));
            return;
        }
        TelemetryLatestEntity patch = new TelemetryLatestEntity();
        patch.setPointValue(value);
        patch.setTimestampMs(timestampMs);
        patch.setWriteId(writeId);
        patch.setUpdatedTime(updatedTime);
        update(patch, new LambdaQueryWrapper<TelemetryLatestEntity>()
                .eq(TelemetryLatestEntity::getTenantId, tenantId)
                .eq(TelemetryLatestEntity::getDeviceId, deviceId)
                .eq(TelemetryLatestEntity::getTelemetryKey, key));
    }

    private static TelemetryLatestEntity newEntity(UUID tenantId, UUID deviceId, String key,
                                                   JsonNode value, long timestampMs,
                                                   UUID writeId, long updatedTime) {
        TelemetryLatestEntity entity = new TelemetryLatestEntity();
        entity.setTenantId(tenantId);
        entity.setDeviceId(deviceId);
        entity.setTelemetryKey(key);
        entity.setPointValue(value);
        entity.setTimestampMs(timestampMs);
        entity.setWriteId(writeId);
        entity.setUpdatedTime(updatedTime);
        return entity;
    }

    private static boolean wins(long newTs, UUID newWriteId, long existingTs, UUID existingWriteId) {
        if (newTs > existingTs) {
            return true;
        }
        if (newTs < existingTs) {
            return false;
        }
        return newWriteId.toString().compareTo(existingWriteId.toString()) > 0;
    }
}
