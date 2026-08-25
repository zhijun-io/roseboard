package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProvider;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.queue.QueueService;
import com.roseboard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class QueueConfigurationIT extends IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
    }

    @Autowired ApplicationContext context;
    @Autowired QueueCoordinator coordinator;
    @Autowired QueueService queueService;
    @Autowired QueueProducerProvider producerProvider;

    @Test
    void memoryProviderIsSelectedAndKafkaProviderAbsent() {
        assertEquals("memory", coordinator.activeProvider());
        assertTrue(context.getBeansOfType(InMemoryQueueProvider.class).size() >= 1);
        assertTrue(context.getBeansOfType(KafkaQueueProvider.class).isEmpty());
        assertTrue(producerProvider instanceof InMemoryQueueProvider);
    }

    @Test
    void unboundQueueIsStoredWithoutManager() {
        UUID tenant = UUID.randomUUID();
        QueueDefinition saved = queueService.create(draft(tenant, "Unbound", "unbound.topic"));
        assertTrue(queueService.findById(tenant, saved.id()).isPresent());
        assertFalse(coordinator.findManager("Unbound").isPresent());
    }

    @Test
    void memoryQueueEndToEndThroughSpring() throws Exception {
        UUID tenant = UUID.randomUUID();
        String queueName = "SmokeMain";
        List<String> processedKeys = new CopyOnWriteArrayList<>();

        coordinator.registerBinding(queueName, "cg-smoke", (id, msg) -> {
                    processedKeys.add(msg.getKey());
                    return CompletableFuture.completedFuture(null);
                });

        QueueDefinition saved = queueService.create(draft(tenant, queueName, "smoke.topic"));
        coordinator.startIfBound(saved.toTransportConfig());
        assertTrue(coordinator.findManager(queueName).isPresent());

        QueueProducer producer = producerProvider.createProducer(saved.toTransportConfig());
        producer.send(
                new TopicPartitionInfo(saved.topic(), null, null, true),
                new DefaultQueueMessage("smoke-key", "payload".getBytes(StandardCharsets.UTF_8), null),
                QueueCallback.EMPTY);
        await(() -> processedKeys.contains("smoke-key"), 5);
        assertEquals(List.of("smoke-key"), processedKeys);

        QueueDefinition updated = queueService.update(tenant, saved.id(), saved.toBuilder()
                .pollInterval(50)
                .build());
        assertEquals(50, updated.pollInterval());
        coordinator.restartIfBound(updated.toTransportConfig());
        assertEquals(50, coordinator.findManager(queueName).orElseThrow().config().pollInterval());

        producer.send(
                new TopicPartitionInfo(updated.topic(), null, null, true),
                new DefaultQueueMessage("smoke-key-2", "p2".getBytes(StandardCharsets.UTF_8), null),
                QueueCallback.EMPTY);
        await(() -> processedKeys.contains("smoke-key-2"), 5);

        coordinator.stopIfBound(queueName);
        assertFalse(coordinator.findManager(queueName).isPresent());

        producer.stop();
        assertTrue(queueService.delete(tenant, saved.id()));
        assertFalse(queueService.findById(tenant, saved.id()).isPresent());
    }

    @Test
    void restartIfBoundAcceptsFullySupportedSubmitStrategy() {
        UUID tenant = UUID.randomUUID();
        String queueName = "RestartRollback";
        coordinator.registerBinding(queueName, "cg-restart", (id, msg) -> CompletableFuture.completedFuture(null));

        QueueDefinition saved = queueService.create(draft(tenant, queueName, "restart.topic"));
        coordinator.startIfBound(saved.toTransportConfig());
        assertEquals(25, coordinator.findManager(queueName).orElseThrow().config().pollInterval());

        QueueDefinition updated = saved.toBuilder()
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.SEQUENTIAL_BY_TENANT, 0))
                .build();
        coordinator.restartIfBound(updated.toTransportConfig());

        QueueConsumerManager<? extends QueueMessage> restarted =
                coordinator.findManager(queueName).orElseThrow();
        assertEquals(25, restarted.config().pollInterval());
        assertEquals(SubmitStrategyType.SEQUENTIAL_BY_TENANT, restarted.config().submitStrategy().type());
    }

    @Test
    void stopIfBoundStopsRunningManager() {
        UUID tenant = UUID.randomUUID();
        coordinator.registerBinding("StopBound", "cg-stop", (id, msg) -> CompletableFuture.completedFuture(null));
        QueueDefinition saved = queueService.create(draft(tenant, "StopBound", "stop.topic"));
        coordinator.startIfBound(saved.toTransportConfig());
        assertTrue(coordinator.findManager("StopBound").isPresent());

        coordinator.stopIfBound("StopBound");
        assertFalse(coordinator.findManager("StopBound").isPresent());
    }

    @Test
    void deleteManagedStopsManagerAndRemovesRecord() {
        UUID tenant = UUID.randomUUID();
        coordinator.registerBinding("ToDelete", "cg-del", (id, msg) -> CompletableFuture.completedFuture(null));
        QueueDefinition saved = queueService.create(draft(tenant, "ToDelete", "del.topic"));
        coordinator.startIfBound(saved.toTransportConfig());
        assertTrue(coordinator.findManager("ToDelete").isPresent());

        queueService.deleteManaged(tenant, saved.id());
        assertFalse(coordinator.findManager("ToDelete").isPresent());
        assertFalse(queueService.findById(tenant, saved.id()).isPresent());
    }

    private static QueueDefinition draft(UUID tenant, String name, String topic) {
        return QueueDefinition.builder()
                .tenantId(tenant)
                .name(name)
                .topic(topic)
                .partitions(1)
                .pollInterval(25)
                .packProcessingTimeout(5_000)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
    }
}
