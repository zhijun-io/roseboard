package com.roseboard.infrastructure.audit.writer.jdbc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保留清理：超过 retention-days 的记录被分页删除，禁用时不产生删除。
 */
class AuditRetentionCleanerTest {

    @Test
    void cleanDeletesOlderThanCutoffInBatches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(5_000, 5_000, 42);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AuditRetentionCleaner cleaner = new AuditRetentionCleaner(jdbcTemplate, 30, registry);
        cleaner.clean();

        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(3))
                .update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(5_000));
        org.junit.jupiter.api.Assertions.assertEquals(10_042.0,
                registry.find("roseboard.audit.retention.deleted").counter().count());
    }

    @Test
    void cleanIsNoOpWhenRetentionDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuditRetentionCleaner cleaner = new AuditRetentionCleaner(jdbcTemplate, 0, new SimpleMeterRegistry());
        cleaner.clean();
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }
}
