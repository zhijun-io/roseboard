package com.roseboard.queue;

import com.roseboard.queue.stats.QueueStats;
import com.roseboard.queue.stats.QueueStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class QueueStatsRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired QueueStatsService statsService;

    @Test
    void enforcesUniquenessAndSupportsLookups() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        QueueStats first = statsService.save(new QueueStats(null, System.currentTimeMillis(), tenantId, "Main", "core-1"));
        assertThrows(DataIntegrityViolationException.class, () ->
                statsService.save(new QueueStats(null, System.currentTimeMillis(), tenantId, "Main", "core-1")));

        QueueStats second = statsService.save(new QueueStats(null, System.currentTimeMillis(), tenantId, "HP", "core-1"));
        statsService.save(new QueueStats(null, System.currentTimeMillis(), otherTenant, "Main", "core-1"));

        Optional<QueueStats> found = statsService.findByTenantIdAndQueueNameAndServiceId(tenantId, "Main", "core-1");
        assertTrue(found.isPresent());
        assertEquals(first.id(), found.get().id());

        List<QueueStats> batch = statsService.findByIds(List.of(first.id(), second.id(), UUID.randomUUID()));
        assertEquals(2, batch.size());

        assertEquals(2, statsService.deleteByTenantId(tenantId));
        assertTrue(statsService.findByTenantIdAndQueueNameAndServiceId(tenantId, "Main", "core-1").isEmpty());
        assertTrue(statsService.findByTenantIdAndQueueNameAndServiceId(otherTenant, "Main", "core-1").isPresent());
    }
}
