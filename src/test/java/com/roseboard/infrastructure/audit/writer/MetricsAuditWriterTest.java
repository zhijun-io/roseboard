package com.roseboard.infrastructure.audit.writer;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.AuditContext;
import com.roseboard.infrastructure.audit.AuditRecord;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.EntityType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsAuditWriterTest {

    @Test
    void writeDelegatesAndCountsSuccess() {
        List<AuditRecord> written = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsAuditWriter writer = new MetricsAuditWriter(record -> written.add(record), registry);

        writer.write(record("USER_CREATED"));
        writer.write(record("LOGIN_SUCCESS"));

        assertEquals(List.of("USER_CREATED", "LOGIN_SUCCESS"), written.stream().map(AuditRecord::action).toList());
        assertEquals(1.0, taggedCount(registry, "roseboard.audit.writes", "USER_CREATED"));
        assertEquals(1.0, taggedCount(registry, "roseboard.audit.writes", "LOGIN_SUCCESS"));
        assertEquals(0.0, registry.find("roseboard.audit.record.failed").counters().stream()
                .mapToDouble(counter -> counter.count()).sum());
    }

    @Test
    void writeFailureIncrementsFailedAndRethrows() {
        AtomicInteger calls = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsAuditWriter writer = new MetricsAuditWriter(record -> {
            calls.incrementAndGet();
            throw new IllegalStateException("storage down");
        }, registry);

        assertThrows(IllegalStateException.class, () -> writer.write(record("USER_DELETED")));
        assertEquals(1, calls.get());
        assertEquals(0.0, countByAction(registry, "roseboard.audit.writes", "USER_DELETED"));
        assertEquals(1.0, countByAction(registry, "roseboard.audit.record.failed", "USER_DELETED"));
    }

    @Test
    void actionTagBucketsUnknownActions() {
        assertEquals("USER_CREATED", MetricsAuditWriter.actionTag("USER_CREATED"));
        assertEquals("OTHER", MetricsAuditWriter.actionTag("weird action!"));
    }

    private static double countByAction(SimpleMeterRegistry registry, String name, String action) {
        return registry.find(name).tag("action", action).counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    private static double taggedCount(SimpleMeterRegistry registry, String name, String action) {
        return countByAction(registry, name, action);
    }

    private static AuditRecord record(String action) {
        return AuditRecord.create(action, SecurityUsers.of(null, null, null),
                AuditTarget.of(EntityType.USER, UUID.randomUUID()), AuditStatus.SUCCEEDED,
                null, null, AuditContext.empty(), null);
    }
}
