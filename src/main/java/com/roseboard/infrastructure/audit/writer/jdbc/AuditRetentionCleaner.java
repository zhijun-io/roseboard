package com.roseboard.infrastructure.audit.writer.jdbc;

import com.roseboard.infrastructure.audit.AuditContext;
import com.roseboard.infrastructure.audit.context.AuditContextHolder;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * 审计保留清理：按 {@code roseboard.audit.retention-days} 删除超过保留期的记录
 * （默认 0 = 禁用）。分页删除防长事务；cron 可配置（默认每天 03:00）。
 */
public class AuditRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionCleaner.class);
    private static final int BATCH_SIZE = 5_000;
    private static final String DELETE_SQL = """
            delete from audit_log
            where occurred_at < ?
              and id in (
                  select id from audit_log
                  where occurred_at < ?
                  order by occurred_at asc
                  limit ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final long retentionDays;
    private final MeterRegistry meterRegistry;

    public AuditRetentionCleaner(JdbcTemplate jdbcTemplate, long retentionDays, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${roseboard.audit.retention-cron:0 0 3 * * *}")
    public void clean() {
        AuditContextHolder.run(AuditContext.of(AuditOrigin.SCHEDULED, "audit-retention", Map.of(
                "job", "audit-retention")), this::cleanInternal);
    }

    private void cleanInternal() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        int deleted;
        long total = 0;
        do {
            Timestamp timestamp = Timestamp.from(cutoff);
            deleted = jdbcTemplate.update(DELETE_SQL, timestamp, timestamp, BATCH_SIZE);
            total += deleted;
        } while (deleted >= BATCH_SIZE);
        if (total > 0) {
            meterRegistry.counter("roseboard.audit.retention.deleted").increment(total);
            log.info("audit retention cleanup removed {} records older than {} days", total, retentionDays);
        }
    }
}
