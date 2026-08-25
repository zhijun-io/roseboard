package com.roseboard.infrastructure.queue.processing;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitStrategyBatchTest {

    private ExecutorService pollExecutor;
    private ExecutorService workerExecutor;
    private QueueConsumerManager.PartitionRunner<QueueMessage> runner;

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
    void submitsEachPollPackToProcessor() throws Exception {
        QueueTransportConfig config = new QueueTransportConfig(
                UUID.randomUUID(), UUID.randomUUID(), "main", "queue.batch", 10,
                1, false, 1000,
                new SubmitStrategy(SubmitStrategyType.BATCH, 3),
                new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0));
        Queue<QueueMessage> messages = new ArrayDeque<>(List.of(message("1"), message("2"), message("3")));
        CountDownLatch submitted = new CountDownLatch(1);
        List<List<String>> batches = new ArrayList<>();

        pollExecutor = Executors.newSingleThreadExecutor();
        workerExecutor = Executors.newSingleThreadExecutor();
        runner = QueueConsumerManager.PartitionRunner.<QueueMessage>builder()
                .pollInterval(10)
                .consumer(new OneByOneConsumer(messages))
                .consumerExecutor(pollExecutor)
                .workerExecutor(workerExecutor)
                .batchSubmit(true)
                .batchSize(3)
                .msgPackProcessor(batch -> {
                    batches.add(batch.stream().map(QueueMessage::getKey).toList());
                    submitted.countDown();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .build();
        runner.subscribe(Set.of(new TopicPartitionInfo(config.topic(), null, 0, false, true)));
        runner.launch();

        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        runner.stop();
        runner.awaitStop(5);
        assertEquals(List.of(List.of("1", "2", "3")), batches);
    }

    private static QueueMessage message(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }

    private static final class OneByOneConsumer implements QueueConsumer<QueueMessage> {
        private final Queue<QueueMessage> messages;
        private Set<TopicPartitionInfo> partitions = Set.of();
        private boolean stopped;

        private OneByOneConsumer(Queue<QueueMessage> messages) {
            this.messages = messages;
        }

        @Override
        public String getTopic() { return "queue.batch"; }

        @Override
        public void subscribe() { }

        @Override
        public void subscribe(Set<TopicPartitionInfo> partitions) { this.partitions = partitions; }

        @Override
        public void stop() { stopped = true; }

        @Override
        public void unsubscribe() { stopped = true; }

        @Override
        public List<QueueMessage> poll(long durationInMillis) {
            QueueMessage message = messages.poll();
            if (message == null) {
                try {
                    Thread.sleep(durationInMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
            return List.of(message);
        }

        @Override
        public void commit() { }

        @Override
        public boolean isStopped() { return stopped; }

        @Override
        public Set<TopicPartitionInfo> getPartitions() { return partitions; }

        @Override
        public List<String> getFullTopicNames() { return List.of("queue.batch-0"); }
    }
}
