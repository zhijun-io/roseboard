package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProducer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class QueueConsumerManagerLifecycleTest {

    private ExecutorService consumerExecutor;
    private ExecutorService workerExecutor;
    private QueueConsumerManager<QueueMessage> manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
            manager.awaitStop();
        }
        shutdown(consumerExecutor, workerExecutor);
    }

    @Test
    void stopCommitsCompletedBatchAndIsIdempotent() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = definition(queueId, "life.commit", false, 5_000);
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        new InMemoryQueueProducer(storage, definition.toTransportConfig()).send(tpi, msg("1"), QueueCallback.EMPTY);

        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        initExecutors();
        manager = QueueConsumerManager.<QueueMessage>builder()
                .queueKey("Main")
                .config(definition.toTransportConfig())
                .consumerCreator((cfg, partition) -> new InMemoryQueueConsumer(storage, cfg, "cg-life"))
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(msgs -> {
                    deliveries.incrementAndGet();
                    delivered.countDown();
                    return CompletableFuture.completedFuture(null);
                })
                .build();

        manager.update(Set.of(tpi));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        awaitCondition(() -> !storage.hasInFlight(queueId, "cg-life", tpi.getFullTopicName()), 5);

        manager.stop();
        manager.awaitStop();
        manager.stop();
        manager.awaitStop();
        assertEquals(1, deliveries.get());
        assertTrue(manager.lifecycleState() == LifecycleState.STOPPED || manager.lifecycleState() == LifecycleState.STOPPING);
    }

    @Test
    void stopTimeoutLeavesBatchUncommittedThenAllowsRedelivery() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = definition(queueId, "life.timeout", false, 100);
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        new InMemoryQueueProducer(storage, definition.toTransportConfig()).send(tpi, msg("x"), QueueCallback.EMPTY);
        new InMemoryQueueProducer(storage, definition.toTransportConfig()).send(tpi, msg("y"), QueueCallback.EMPTY);

        CountDownLatch delivered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        initExecutors();
        manager = QueueConsumerManager.<QueueMessage>builder()
                .queueKey("Main")
                .config(definition.toTransportConfig())
                .consumerCreator((cfg, partition) -> new InMemoryQueueConsumer(storage, cfg, "cg-timeout"))
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(msgs -> {
                    delivered.countDown();
                    return gate;
                })
                .build();

        manager.update(Set.of(tpi));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));

        manager.stop();
        manager.awaitStop();
        manager = null;

        InMemoryQueueConsumer recovery = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "cg-timeout");
        recovery.subscribe(Set.of(tpi));
        if (storage.hasInFlight(queueId, "cg-timeout", tpi.getFullTopicName())) {
            recovery.restoreInFlight();
        }
        List<String> keys = recovery.poll(50).stream().map(QueueMessage::getKey).toList();
        assertEquals(List.of("x", "y"), keys);
        gate.complete(null);
    }

    private void initExecutors() {
        consumerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "life-consumer"));
        workerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "life-worker"));
    }

    private static QueueDefinition definition(UUID id, String topic, boolean consumerPerPartition, long timeout) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(2)
                .pollInterval(20)
                .consumerPerPartition(consumerPerPartition)
                .packProcessingTimeout(timeout)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void awaitCondition(Check check, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not met");
    }

    private static void shutdown(ExecutorService... executors) {
        for (ExecutorService executor : executors) {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
