package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProducer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager.PartitionRunner;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager.PartitionRunner.RunnerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class QueueConsumerManagerBatchTest {

    private ExecutorService pollExecutor;
    private ExecutorService workerExecutor;
    private PartitionRunner<QueueMessage> runner;

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
            runner.awaitStop(5);
        }
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void incompleteStageBlocksNextBatchAndCommitUntilComplete() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = definition(queueId, "batch.block");
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        InMemoryQueueProducer producer = new InMemoryQueueProducer(storage, definition.toTransportConfig());
        producer.send(tpi, msg("1"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);
        producer.send(tpi, msg("2"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);
        producer.send(tpi, msg("3"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);

        InMemoryQueueConsumer consumer = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "cg-block");
        CountDownLatch firstDelivered = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<CompletableFuture<Void>> gate = new AtomicReference<>(new CompletableFuture<>());
        List<List<String>> batches = new CopyOnWriteArrayList<>();

        pollExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "poll-block"));
        workerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "worker-block"));

        runner = PartitionRunner.<QueueMessage>builder()
                .pollInterval(20)
                .consumer(consumer)
                .consumerExecutor(pollExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(msgs -> {
                    deliveries.incrementAndGet();
                    batches.add(msgs.stream().map(QueueMessage::getKey).toList());
                    firstDelivered.countDown();
                    return gate.get();
                })
                .build();

        runner.subscribe(Set.of(tpi));
        runner.launch();

        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, deliveries.get());
        assertEquals(List.of(List.of("1", "2", "3")), batches);

        Thread.sleep(150);
        assertEquals(1, deliveries.get());
        assertTrue(storage.hasInFlight(queueId, "cg-block", tpi.getFullTopicName()));

        gate.get().complete(null);
        awaitCondition(() -> !storage.hasInFlight(queueId, "cg-block", tpi.getFullTopicName()), 5);

        producer.send(tpi, msg("4"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);
        awaitCondition(() -> deliveries.get() >= 2, 5);
        assertEquals("4", batches.get(1).get(0));
    }

    @Test
    void commitFailureMarksFailedAndAllowsWholeBatchRedelivery() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = definition(queueId, "batch.commitfail");
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        InMemoryQueueProducer producer = new InMemoryQueueProducer(storage, definition.toTransportConfig());
        producer.send(tpi, msg("a"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);
        producer.send(tpi, msg("b"), com.roseboard.infrastructure.queue.spi.QueueCallback.EMPTY);

        InMemoryQueueConsumer inner = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "cg-fail");
        FailingCommitConsumer consumer = new FailingCommitConsumer(inner);

        CountDownLatch delivered = new CountDownLatch(1);
        pollExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "poll-fail"));
        workerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "worker-fail"));

        runner = PartitionRunner.<QueueMessage>builder()
                .pollInterval(20)
                .consumer(consumer)
                .consumerExecutor(pollExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(msgs -> {
                    delivered.countDown();
                    return CompletableFuture.completedFuture(null);
                })
                .build();

        runner.subscribe(Set.of(tpi));
        runner.launch();

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        awaitCondition(() -> runner.state() == RunnerState.FAILED, 5);

        runner.stop();
        runner.awaitStop(5);
        runner = null;

        InMemoryQueueConsumer recovery = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "cg-fail");
        recovery.subscribe(Set.of(tpi));
        if (storage.hasInFlight(queueId, "cg-fail", tpi.getFullTopicName())) {
            recovery.restoreInFlight();
        }
        List<QueueMessage> redelivered = recovery.poll(50);
        assertEquals(List.of("a", "b"), redelivered.stream().map(QueueMessage::getKey).toList());
    }

    private static QueueDefinition definition(UUID id, String topic) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(1)
                .pollInterval(20)
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
        fail("condition not met within " + seconds + "s");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class FailingCommitConsumer implements QueueConsumer<QueueMessage> {
        private final InMemoryQueueConsumer delegate;

        FailingCommitConsumer(InMemoryQueueConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getTopic() {
            return delegate.getTopic();
        }

        @Override
        public void subscribe() {
            delegate.subscribe();
        }

        @Override
        public void subscribe(Set<TopicPartitionInfo> partitions) {
            delegate.subscribe(partitions);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void unsubscribe() {
            delegate.unsubscribe();
        }

        @Override
        public List<QueueMessage> poll(long durationInMillis) {
            return delegate.poll(durationInMillis);
        }

        @Override
        public void commit() {
            throw new IllegalStateException("commit failed");
        }

        @Override
        public boolean isStopped() {
            return delegate.isStopped();
        }

        @Override
        public Set<TopicPartitionInfo> getPartitions() {
            return delegate.getPartitions();
        }

        @Override
        public List<String> getFullTopicNames() {
            return delegate.getFullTopicNames();
        }
    }
}
