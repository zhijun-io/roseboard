package com.roseboard.device.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TelemetryRetentionService {
    private final TelemetryPointMapper pointMapper;
    private final long retentionMs;
    private final int cleanupBatchSize;
    private final MeterRegistry meterRegistry;

    public TelemetryRetentionService(TelemetryPointMapper pointMapper,
                                     @Value("${roseboard.telemetry.retention-ms:2592000000}") long retentionMs,
                                     @Value("${roseboard.telemetry.cleanup-batch-size:1000}") int cleanupBatchSize,
                                     MeterRegistry meterRegistry) {
        if (retentionMs < 1) {
            throw new IllegalArgumentException("telemetry retention-ms must be positive");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException("telemetry cleanup-batch-size must be between 1 and 10000");
        }
        this.pointMapper = pointMapper;
        this.retentionMs = retentionMs;
        this.cleanupBatchSize = cleanupBatchSize;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(rollbackFor = Exception.class)
    public int cleanupExpired(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("cleanup clock is required");
        }
        long cutoffMs = now.toEpochMilli() - retentionMs;
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = pointMapper.deleteExpiredBatch(cutoffMs, cleanupBatchSize);
            totalDeleted += deleted;
        } while (deleted == cleanupBatchSize);
        if (totalDeleted > 0) {
            meterRegistry.counter("roseboard.telemetry.retention.deleted").increment(totalDeleted);
        }
        return totalDeleted;
    }
}
