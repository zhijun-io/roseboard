package com.roseboard.device.telemetry;

import com.roseboard.cache.TelemetryLatestCacheEvictionEvent;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.JacksonCacheCodec;
import com.roseboard.infrastructure.cache.CacheCodec;
import com.roseboard.common.JacksonUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.tenant.usage.TenantRateLimitService;
import com.roseboard.tenant.usage.TenantUsageService;

@Service
public class TelemetryService implements DeviceTelemetryStore {
    public static final int MAX_BATCH_SIZE = 100;
    public static final long MAX_FUTURE_SKEW_SECONDS = 300;

    private static final CacheCodec<TelemetryLatest> CACHE_CODEC =
            new JacksonCacheCodec<>(TelemetryLatest.class);

    private final TelemetryPointMapper mapper;
    private final TelemetryLatestMapper latestMapper;
    private final DeviceMapper deviceMapper;
    private final TenantUsageService usageService;
    private final TenantRateLimitService rateLimitService;
    private final ApplicationEventPublisher events;
    private final CacheTemplate cache;
    private final CacheProperties cacheProperties;
    private final CacheEvictor cacheEvictions;

    public TelemetryService(TelemetryPointMapper mapper, TelemetryLatestMapper latestMapper,
                            DeviceMapper deviceMapper, TenantUsageService usageService,
                            TenantRateLimitService rateLimitService,
                            ApplicationEventPublisher events,
                            CacheTemplate cache, CacheProperties cacheProperties,
                            CacheEvictor cacheEvictions) {
        this.mapper = mapper;
        this.latestMapper = latestMapper;
        this.deviceMapper = deviceMapper;
        this.usageService = usageService;
        this.rateLimitService = rateLimitService;
        this.events = events;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.cacheEvictions = cacheEvictions;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveFromDevice(DeviceCredentialService.DevicePrincipal principal, String key, Object value,
                               Instant timestamp, UUID writeId) {
        save(principal.tenantId(), principal.deviceId(), key, value, timestamp, writeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(UUID tenantId, UUID deviceId, String key, Object value,
                     Instant timestamp, UUID writeId) {
        requireDeviceInTenant(tenantId, deviceId);
        TelemetryWrite write = validatedWrite(key, value, timestamp, writeId);
        boolean novel = persistValidated(tenantId, deviceId, write);
        if (novel) {
            usageService.beforeTransportWrite(tenantId, deviceId, 1, 1, rateLimitService);
        }
        if (novel) {
            usageService.recordTransportWrite(tenantId, 1, 1);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBatch(UUID tenantId, UUID deviceId, List<TelemetryWrite> writes) {
        requireDeviceInTenant(tenantId, deviceId);
        if (writes == null || writes.isEmpty() || writes.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Telemetry batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<TelemetryWrite> validated = writes.stream()
                .map(write -> validatedWrite(write.key(), write.value(), write.timestamp(), write.writeId()))
                .toList();
        int novelCount = 0;
        for (TelemetryWrite write : validated) {
            if (persistValidated(tenantId, deviceId, write)) {
                novelCount++;
            }
        }
        if (novelCount > 0) {
            usageService.beforeTransportWrite(tenantId, deviceId, 1, novelCount, rateLimitService);
        }
        if (novelCount > 0) {
            usageService.recordTransportWrite(tenantId, 1, novelCount);
        }
    }

    @Override
    public TelemetryLatest latest(UUID tenantId, UUID deviceId, String key) {
        requireDeviceInTenant(tenantId, deviceId);
        new AttributeKey(key);
        String cacheKey = CacheKeyBuilder.telemetryLatest(tenantId, deviceId, key);
        return cache.get(cacheProperties.spec("tsLatest"), cacheKey,
                () -> toLatest(latestMapper.find(tenantId, deviceId, key)), CACHE_CODEC);
    }
    @Override
    public List<TelemetryLatest> latest(UUID tenantId, UUID deviceId, Collection<String> keys) {
        requireDeviceInTenant(tenantId, deviceId);
        List<String> validatedKeys = validatedKeys(keys);
        return validatedKeys.stream()
                .map(key -> latest(tenantId, deviceId, key))
                .filter(latest -> latest != null)
                .toList();
    }

    @Override
    public List<TelemetryLatest> latestAll(UUID tenantId, UUID deviceId) {
        requireDeviceInTenant(tenantId, deviceId);
        return latestMapper.keys(tenantId, deviceId).stream()
                .map(key -> latest(tenantId, deviceId, key))
                .toList();
    }

    @Override
    public List<String> keys(UUID tenantId, UUID deviceId) {
        requireDeviceInTenant(tenantId, deviceId);
        return latestMapper.keys(tenantId, deviceId);
    }

    @Override
    public List<TelemetryPoint> history(UUID tenantId, UUID deviceId, String key,
                                        long from, long to, int limit) {
        return history(tenantId, deviceId, List.of(key), from, to, limit, TelemetryOrder.DESC);
    }

    @Override
    public List<TelemetryPoint> history(UUID tenantId, UUID deviceId, Collection<String> keys,
                                        long from, long to, int limit, TelemetryOrder order) {
        requireDeviceInTenant(tenantId, deviceId);
        List<String> validatedKeys = validatedKeys(keys);
        if (from > to || limit < 1 || limit > MAX_HISTORY_LIMIT || order == null) {
            throw new IllegalArgumentException("Invalid telemetry history range");
        }
        return mapper.findHistory(tenantId, deviceId, validatedKeys, from, to, limit, order).stream()
                .map(TelemetryService::toPoint)
                .toList();
    }

    @Override
    public List<TelemetryBucket> aggregate(UUID tenantId, UUID deviceId, String key,
                                           long from, long to, TelemetryAggregation aggregation,
                                           long intervalMs) {
        requireDeviceInTenant(tenantId, deviceId);
        new AttributeKey(key);
        if (aggregation == null || from > to || intervalMs < 1) {
            throw new IllegalArgumentException("Invalid telemetry aggregation request");
        }
        long buckets = (to - from) / intervalMs + 1;
        if (buckets > MAX_AGGREGATION_BUCKETS) {
            throw new IllegalArgumentException(
                    "Telemetry aggregation exceeds " + MAX_AGGREGATION_BUCKETS + " buckets");
        }
        List<TelemetryPointEntity> points = mapper.findRange(tenantId, deviceId, key, from, to);
        return aggregateInMemory(points, aggregation, intervalMs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int remove(UUID tenantId, UUID deviceId, String key,
                      long from, long to, boolean rewriteLatest) {
        requireDeviceInTenant(tenantId, deviceId);
        new AttributeKey(key);
        if (from > to) {
            throw new IllegalArgumentException("Invalid telemetry delete range");
        }
        int deleted = mapper.deleteRange(tenantId, deviceId, key, from, to);
        if (rewriteLatest) {
            TelemetryPointEntity newest = mapper.findNewest(tenantId, deviceId, key);
            if (newest == null) {
                latestMapper.deleteByKey(tenantId, deviceId, key);
            } else {
                latestMapper.replace(tenantId, deviceId, key, newest.getPointValue(),
                        newest.getTimestampMs(), newest.getWriteId(), System.currentTimeMillis());
            }
        }
        cacheEvictions.publish(new TelemetryLatestCacheEvictionEvent(
                tenantId, deviceId, key, null));
        return deleted;
    }

    private boolean persistValidated(UUID tenantId, UUID deviceId, TelemetryWrite write) {
        JsonNode valueNode = JacksonUtils.objectMapper().valueToTree(write.value());
        long timestampMs = write.timestamp().toEpochMilli();
        long createdTime = System.currentTimeMillis();

        int inserted = mapper.insertIfAbsent(UUID.randomUUID(), tenantId, deviceId, write.key(),
                JacksonUtils.toCanonicalString(valueNode), timestampMs, write.writeId(), createdTime);
        if (inserted == 0) {
            TelemetryPointEntity existing = mapper.findByWriteId(tenantId, deviceId, write.writeId());
            if (existing == null) {
                throw new IllegalStateException("Telemetry writeId insert outcome is unavailable");
            }
            if (!existing.getTelemetryKey().equals(write.key())
                    || !existing.getTimestampMs().equals(timestampMs)
                    || !existing.getPointValue().equals(valueNode)) {
                throw new IllegalStateException("Telemetry writeId conflicts with existing content");
            }
            return false;
        }

        latestMapper.upsert(tenantId, deviceId, write.key(), valueNode,
                timestampMs, write.writeId(), createdTime);
        cacheEvictions.publish(new TelemetryLatestCacheEvictionEvent(
                tenantId, deviceId, write.key(), null));
        events.publishEvent(new DeviceTelemetrySavedEvent(tenantId, deviceId, write.key(),
                JacksonUtils.convertValue(valueNode, Object.class), timestampMs));
        return true;
    }

    private static List<TelemetryBucket> aggregateInMemory(List<TelemetryPointEntity> points,
                                                           TelemetryAggregation aggregation,
                                                           long intervalMs) {
        Map<Long, BucketAcc> buckets = new LinkedHashMap<>();
        for (TelemetryPointEntity point : points) {
            long bucketTs = (point.getTimestampMs() / intervalMs) * intervalMs;
            BucketAcc acc = buckets.computeIfAbsent(bucketTs, ignored -> new BucketAcc());
            if (aggregation == TelemetryAggregation.COUNT) {
                acc.count++;
                continue;
            }
            if (!point.getPointValue().isNumber()) {
                continue;
            }
            double number = point.getPointValue().asDouble();
            acc.count++;
            acc.sum += number;
            acc.min = Math.min(acc.min, number);
            acc.max = Math.max(acc.max, number);
        }
        List<TelemetryBucket> result = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, BucketAcc> entry : buckets.entrySet()) {
            BucketAcc acc = entry.getValue();
            if (acc.count == 0 && aggregation != TelemetryAggregation.COUNT) {
                continue;
            }
            double value = switch (aggregation) {
                case MIN -> acc.min;
                case MAX -> acc.max;
                case AVG -> acc.sum / acc.count;
                case SUM -> acc.sum;
                case COUNT -> acc.count;
            };
            result.add(new TelemetryBucket(entry.getKey(), value));
        }
        return result;
    }

    private static TelemetryWrite validatedWrite(String key, Object value,
                                                 Instant timestamp, UUID writeId) {
        if (key == null || value == null || timestamp == null || writeId == null) {
            throw new IllegalArgumentException("telemetry point is incomplete");
        }
        if (timestamp.isAfter(Instant.now().plusSeconds(MAX_FUTURE_SKEW_SECONDS))) {
            throw new IllegalArgumentException("telemetry timestamp is too far in the future");
        }
        new AttributeKey(key);
        new AttributeValue(value);
        return new TelemetryWrite(key, value, timestamp, writeId);
    }

    private static List<String> validatedKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "Telemetry key batch size must be between 1 and " + MAX_KEYS);
        }
        List<String> validated = new ArrayList<>(keys.size());
        for (String key : keys) {
            validated.add(new AttributeKey(key).value());
        }
        return validated;
    }

    private void requireDeviceInTenant(UUID tenantId, UUID deviceId) {
        if (tenantId == null || deviceId == null) {
            throw new IllegalArgumentException("Telemetry scope is required");
        }
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new IllegalArgumentException("Device does not belong to tenant");
        }
    }

    private static TelemetryLatest toLatest(TelemetryLatestEntity entity) {
        if (entity == null) {
            return null;
        }
        return new TelemetryLatest(entity.getTenantId(), entity.getDeviceId(),
                entity.getTelemetryKey(),
                JacksonUtils.convertValue(entity.getPointValue(), Object.class),
                entity.getTimestampMs(), entity.getWriteId());
    }

    private static TelemetryPoint toPoint(TelemetryPointEntity entity) {
        return new TelemetryPoint(entity.getTenantId(), entity.getDeviceId(),
                entity.getTelemetryKey(),
                JacksonUtils.convertValue(entity.getPointValue(), Object.class),
                entity.getTimestampMs(), entity.getWriteId());
    }

    private static final class BucketAcc {
        long count;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
    }
}
