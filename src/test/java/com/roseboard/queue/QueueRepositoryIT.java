package com.roseboard.queue;

import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class QueueRepositoryIT {
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

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    QueueService queueService;

    @Test
    void persistsEveryColumnAndPreservesUnknownAdditionalInfo() {
        UUID tenantId = UUID.randomUUID();
        JsonNode additional = MAPPER.readTree("""
                {"contentType":"application/x-protobuf","schemaVersion":3,"maxMessageBytes":2048,
                "maxPendingRequests":50,"duplicateMsgToAllPartitions":true,
                "customProperties":"acks=all","legacyFlag":true,"nested":{"k":1}}
                """);

        QueueDefinition saved = queueService.create(newQueue(
                tenantId, "Main", "tb_rule_engine.main", 25, 10, true, 2000L,
                submit(SubmitStrategyType.BATCH, 32),
                processing(ProcessingStrategyType.RETRY_FAILED, 3, 10.5, 100L, 1000L),
                additional));

        Optional<QueueDefinition> loaded = queueService.findById(tenantId, saved.id());
        assertTrue(loaded.isPresent());
        QueueDefinition q = loaded.get();
        assertEquals(tenantId, q.tenantId());
        assertEquals("Main", q.name());
        assertEquals("tb_rule_engine.main", q.topic());
        assertEquals(25, q.pollInterval());
        assertEquals(10, q.partitions());
        assertTrue(q.consumerPerPartition());
        assertEquals(2000L, q.packProcessingTimeout());
        assertEquals(SubmitStrategyType.BATCH, q.submitStrategy().type());
        assertEquals(32, q.submitStrategy().batchSize());
        assertEquals(ProcessingStrategyType.RETRY_FAILED, q.processingStrategy().type());
        assertEquals(3, q.processingStrategy().retries());
        assertEquals(10.5, q.processingStrategy().failurePercentage());
        assertEquals(100L, q.processingStrategy().pauseBetweenRetries());
        assertEquals(1000L, q.processingStrategy().maxPauseBetweenRetries());
        assertTrue(q.additionalInfo().get("legacyFlag").asBoolean());
        assertEquals(1, q.additionalInfo().get("nested").get("k").asInt());
        assertEquals("application/x-protobuf", q.additionalInfo().get("contentType").asText());
        assertEquals(3, q.additionalInfo().get("schemaVersion").asInt());
        assertEquals(2048, q.additionalInfo().get("maxMessageBytes").asInt());
        assertEquals(50, q.additionalInfo().get("maxPendingRequests").asInt());
        assertTrue(q.additionalInfo().get("duplicateMsgToAllPartitions").asBoolean());
        assertEquals("acks=all", q.additionalInfo().get("customProperties").asText());
    }

    @Test
    void findsByNameTopicAndListsByTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        queueService.create(newQueue(tenantA, "Main", "topic.a.main",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0)));
        queueService.create(newQueue(tenantA, "HP", "topic.a.hp",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0)));
        queueService.create(newQueue(tenantB, "Main", "topic.b.main",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0)));

        assertEquals("Main", queueService.findByName(tenantA, "Main").orElseThrow().name());
        assertEquals("topic.a.hp", queueService.findByTopic(tenantA, "topic.a.hp").orElseThrow().topic());

        List<QueueDefinition> tenantQueues = queueService.findAllByTenantId(tenantA);
        assertEquals(2, tenantQueues.size());
        assertTrue(tenantQueues.stream().allMatch(q -> tenantA.equals(q.tenantId())));
        assertTrue(tenantQueues.stream().anyMatch(q -> "Main".equals(q.name())));
        assertTrue(tenantQueues.stream().anyMatch(q -> "HP".equals(q.name())));
    }

    @Test
    void rejectsDuplicatesAndInvalidFields() {
        UUID tenantId = UUID.randomUUID();
        queueService.create(newQueue(tenantId, "Main", "topic.main",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0)));

        assertThrows(IllegalArgumentException.class, () -> queueService.create(newQueue(
                tenantId, "Main", "topic.other",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> queueService.create(newQueue(
                tenantId, "Other", "topic.main",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> queueService.create(newQueue(
                tenantId, "", "topic.empty-name",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> queueService.create(newQueue(
                tenantId, "BadParts", "topic.parts", 25, 0, false, 2000L,
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0),
                null)));
        assertThrows(IllegalArgumentException.class, () -> queueService.create(newQueue(
                tenantId, "BadBatch", "topic.batch", 25, 1, false, 2000L,
                submit(SubmitStrategyType.BATCH, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0),
                null)));
    }

    @Test
    void tenantScopedLookupUpdateAndDelete() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        QueueDefinition created = queueService.create(newQueue(tenantId, "Main", "topic.upd",
                submit(SubmitStrategyType.BURST, 0),
                processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0)));

        assertTrue(queueService.findById(tenantId, created.id()).isPresent());
        assertTrue(queueService.findById(otherTenant, created.id()).isEmpty());

        QueueDefinition updated = queueService.update(tenantId, created.id(), newQueue(
                tenantId, "Main", "topic.upd", 50, 4, true, 5000L,
                submit(SubmitStrategyType.SEQUENTIAL, 0),
                processing(ProcessingStrategyType.RETRY_ALL, 1, 0, 10L, 20L),
                MAPPER.createObjectNode().put("note", "x")));
        assertEquals(50, updated.pollInterval());
        assertEquals(4, updated.partitions());
        assertEquals(SubmitStrategyType.SEQUENTIAL, updated.submitStrategy().type());
        assertEquals("x", updated.additionalInfo().get("note").asString());

        assertThrows(IllegalArgumentException.class, () -> queueService.update(tenantId, created.id(),
                newQueue(tenantId, "Renamed", "topic.upd",
                        submit(SubmitStrategyType.BURST, 0),
                        processing(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))));

        assertFalse(queueService.delete(otherTenant, created.id()));
        assertTrue(queueService.delete(tenantId, created.id()));
        assertTrue(queueService.findById(tenantId, created.id()).isEmpty());
    }

    private static QueueDefinition newQueue(UUID tenantId, String name, String topic,
                                            SubmitStrategy submit, ProcessingStrategy processing) {
        return newQueue(tenantId, name, topic, 25, 10, false, 2000L, submit, processing, null);
    }

    private static QueueDefinition newQueue(UUID tenantId, String name, String topic,
                                            int pollInterval, int partitions, boolean consumerPerPartition,
                                            long packProcessingTimeout,
                                            SubmitStrategy submit, ProcessingStrategy processing,
                                            JsonNode additionalInfo) {
        return QueueDefinition.builder()
                .tenantId(tenantId)
                .name(name)
                .topic(topic)
                .pollInterval(pollInterval)
                .partitions(partitions)
                .consumerPerPartition(consumerPerPartition)
                .packProcessingTimeout(packProcessingTimeout)
                .submitStrategy(submit)
                .processingStrategy(processing)
                .additionalInfo(additionalInfo)
                .build();
    }

    private static SubmitStrategy submit(SubmitStrategyType type, int batchSize) {
        return new SubmitStrategy(type, batchSize);
    }

    private static ProcessingStrategy processing(ProcessingStrategyType type, int retries,
                                                 double failurePercentage, long pause, long maxPause) {
        return new ProcessingStrategy(type, retries, failurePercentage, pause, maxPause);
    }
}
