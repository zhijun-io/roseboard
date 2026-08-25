package com.roseboard.device.telemetry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Mapper
public interface TelemetryPointMapper extends BaseMapper<TelemetryPointEntity> {

    @Insert("""
            insert into telemetry_point
                (id, tenant_id, device_id, telemetry_key, point_value, timestamp_ms, write_id, created_time)
            values
                (#{id}, #{tenantId}, #{deviceId}, #{telemetryKey}, cast(#{pointValueJson} as jsonb),
                 #{timestampMs}, #{writeId}, #{createdTime})
            on conflict (tenant_id, device_id, write_id) do nothing
            """)
    int insertIfAbsent(UUID id, UUID tenantId, UUID deviceId, String telemetryKey,
                       String pointValueJson, long timestampMs, UUID writeId, long createdTime);

    default TelemetryPointEntity findByWriteId(UUID tenantId, UUID deviceId, UUID writeId) {
        return selectOne(new LambdaQueryWrapper<TelemetryPointEntity>()
                .eq(TelemetryPointEntity::getTenantId, tenantId)
                .eq(TelemetryPointEntity::getDeviceId, deviceId)
                .eq(TelemetryPointEntity::getWriteId, writeId));
    }

    default List<TelemetryPointEntity> findHistory(UUID tenantId, UUID deviceId, String key,
                                                   long from, long to, int limit) {
        return findHistory(tenantId, deviceId, List.of(key), from, to, limit, TelemetryOrder.DESC);
    }

    default List<TelemetryPointEntity> findHistory(UUID tenantId, UUID deviceId, Collection<String> keys,
                                                   long from, long to, int limit, TelemetryOrder order) {
        var query = new LambdaQueryWrapper<TelemetryPointEntity>()
                .eq(TelemetryPointEntity::getTenantId, tenantId)
                .eq(TelemetryPointEntity::getDeviceId, deviceId)
                .in(TelemetryPointEntity::getTelemetryKey, keys)
                .ge(TelemetryPointEntity::getTimestampMs, from)
                .le(TelemetryPointEntity::getTimestampMs, to);
        if (order == TelemetryOrder.ASC) {
            query.orderByAsc(TelemetryPointEntity::getTimestampMs)
                    .orderByAsc(TelemetryPointEntity::getTelemetryKey);
        } else {
            query.orderByDesc(TelemetryPointEntity::getTimestampMs)
                    .orderByAsc(TelemetryPointEntity::getTelemetryKey);
        }
        return selectList(query.last("limit " + limit));
    }

    default List<TelemetryPointEntity> findRange(UUID tenantId, UUID deviceId, String key,
                                                 long from, long to) {
        return selectList(new LambdaQueryWrapper<TelemetryPointEntity>()
                .eq(TelemetryPointEntity::getTenantId, tenantId)
                .eq(TelemetryPointEntity::getDeviceId, deviceId)
                .eq(TelemetryPointEntity::getTelemetryKey, key)
                .ge(TelemetryPointEntity::getTimestampMs, from)
                .le(TelemetryPointEntity::getTimestampMs, to)
                .orderByAsc(TelemetryPointEntity::getTimestampMs));
    }

    default TelemetryPointEntity findNewest(UUID tenantId, UUID deviceId, String key) {
        return selectOne(new LambdaQueryWrapper<TelemetryPointEntity>()
                .eq(TelemetryPointEntity::getTenantId, tenantId)
                .eq(TelemetryPointEntity::getDeviceId, deviceId)
                .eq(TelemetryPointEntity::getTelemetryKey, key)
                .orderByDesc(TelemetryPointEntity::getTimestampMs)
                .orderByDesc(TelemetryPointEntity::getWriteId)
                .last("limit 1"));
    }

    default int deleteRange(UUID tenantId, UUID deviceId, String key, long from, long to) {
        return delete(new LambdaQueryWrapper<TelemetryPointEntity>()
                .eq(TelemetryPointEntity::getTenantId, tenantId)
                .eq(TelemetryPointEntity::getDeviceId, deviceId)
                .eq(TelemetryPointEntity::getTelemetryKey, key)
                .ge(TelemetryPointEntity::getTimestampMs, from)
                .le(TelemetryPointEntity::getTimestampMs, to));
    }

    default int deleteExpiredBatch(long cutoffMs, int limit) {
        List<TelemetryPointEntity> expired = selectList(new LambdaQueryWrapper<TelemetryPointEntity>()
                .lt(TelemetryPointEntity::getTimestampMs, cutoffMs)
                .orderByAsc(TelemetryPointEntity::getTimestampMs)
                .last("limit " + limit));
        if (expired.isEmpty()) {
            return 0;
        }
        List<UUID> ids = expired.stream().map(TelemetryPointEntity::getId).toList();
        return delete(new LambdaQueryWrapper<TelemetryPointEntity>()
                .in(TelemetryPointEntity::getId, ids));
    }
}
