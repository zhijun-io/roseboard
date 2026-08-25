package com.roseboard.infrastructure.audit.writer;

import com.roseboard.infrastructure.audit.AuditRecord;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 可观测性装饰器：包装任意 {@link AuditWriter}，在委托写入时记录指标。
 */
public class MetricsAuditWriter implements AuditWriter {

    private static final int MAX_ACTION_TAG_LENGTH = 64;

    private final AuditWriter delegate;
    private final MeterRegistry meterRegistry;

    public MetricsAuditWriter(AuditWriter delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void write(AuditRecord record) {
        try {
            delegate.write(record);
            meterRegistry.counter("roseboard.audit.writes",
                    "status", record.status().name(),
                    "action", actionTag(record.action())).increment();
        } catch (RuntimeException failure) {
            meterRegistry.counter("roseboard.audit.record.failed",
                    "status", record.status().name(),
                    "action", actionTag(record.action())).increment();
            throw failure;
        }
    }

    public static String actionTag(String action) {
        if (action == null || action.isBlank()) {
            return "UNKNOWN";
        }
        if (action.length() > MAX_ACTION_TAG_LENGTH || !action.chars().allMatch(ch ->
                Character.isUpperCase(ch) || ch == '_' || Character.isDigit(ch))) {
            return "OTHER";
        }
        return action;
    }
}
