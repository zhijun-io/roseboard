package com.roseboard.device.telemetry;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeviceTelemetryStore {
    int MAX_KEYS = 100;
    int MAX_HISTORY_LIMIT = 500;
    int MAX_AGGREGATION_BUCKETS = 500;

    void save(UUID tenantId, UUID deviceId, String key, Object value,
              Instant timestamp, UUID writeId);

    void saveBatch(UUID tenantId, UUID deviceId, List<TelemetryWrite> writes);

    TelemetryLatest latest(UUID tenantId, UUID deviceId, String key);

    List<TelemetryLatest> latest(UUID tenantId, UUID deviceId, Collection<String> keys);

    List<TelemetryLatest> latestAll(UUID tenantId, UUID deviceId);

    List<String> keys(UUID tenantId, UUID deviceId);

    List<TelemetryPoint> history(UUID tenantId, UUID deviceId, String key,
                                 long from, long to, int limit);

    List<TelemetryPoint> history(UUID tenantId, UUID deviceId, Collection<String> keys,
                                 long from, long to, int limit, TelemetryOrder order);

    List<TelemetryBucket> aggregate(UUID tenantId, UUID deviceId, String key,
                                    long from, long to, TelemetryAggregation aggregation,
                                    long intervalMs);

    int remove(UUID tenantId, UUID deviceId, String key,
               long from, long to, boolean rewriteLatest);
}
