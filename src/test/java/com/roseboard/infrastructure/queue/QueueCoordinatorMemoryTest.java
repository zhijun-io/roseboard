package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.MemoryQueueAdmin;
import com.roseboard.queue.QueueDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueCoordinatorMemoryTest {

    @Test
    void consumesMessageThroughRuntimeService() throws Exception {
        InMemoryQueueProvider provider = new InMemoryQueueProvider();
        var consumerExecutor = Executors.newSingleThreadExecutor();
        var workerExecutor = Executors.newSingleThreadExecutor();
        try {
            QueueProperties properties = new QueueProperties();
            QueueCoordinator service = new QueueCoordinator(
                    properties, provider, provider, new MemoryQueueAdmin(), consumerExecutor, workerExecutor);
            CountDownLatch handled = new CountDownLatch(1);
            service.registerBinding("SmokeMain", "cg-smoke", (id, message) -> {
                handled.countDown();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
            QueueDefinition definition = QueueDefinition.builder()
                    .id(UUID.randomUUID())
                    .tenantId(UUID.randomUUID())
                    .name("SmokeMain")
                    .topic("smoke.topic")
                    .partitions(1)
                    .pollInterval(25)
                    .packProcessingTimeout(5_000)
                    .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                    .processingStrategy(new ProcessingStrategy(
                            ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                    .build();
            service.startIfBound(definition.toTransportConfig());
            provider.createProducer(definition.toTransportConfig()).send(
                    new TopicPartitionInfo(definition.topic(), null, null, true),
                    new DefaultQueueMessage("key", "data".getBytes(StandardCharsets.UTF_8), null),
                    QueueCallback.EMPTY);

            assertTrue(handled.await(2, TimeUnit.SECONDS));
            service.stopIfBound(definition.name());
        } finally {
            consumerExecutor.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }
}
