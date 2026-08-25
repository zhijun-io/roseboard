package com.roseboard.infrastructure.queue.deadletter;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProducer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.adapter.memory.MemoryQueueAdmin;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import com.roseboard.infrastructure.queue.processing.QueueMsgPackPipeline;
import com.roseboard.queue.QueueDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

class QueueFailureDeadLetterTest {

    private ExecutorService consumerExecutor;
    private ExecutorService workerExecutor;
    private QueueConsumerManager<QueueMessage> manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
            manager.awaitStop();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void retryExhaustedMessageAppearsOnDlqAndIsNotRedelivered() throws Exception {
        UUID queueId = UUID.randomUUID();
        String topic = "fail.main";
        QueueDefinition definition = definition(queueId, topic);
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        InMemoryQueueProvider provider = new InMemoryQueueProvider(storage);
        MemoryQueueAdmin admin = new MemoryQueueAdmin();
        DeadLetterPublisher deadLetterPublisher = DeadLetterPublisher.create(provider, admin, definition.toTransportConfig());

        TopicPartitionInfo partition0 = new TopicPartitionInfo(topic, null, 0, false, true);
        new InMemoryQueueProducer(storage, definition.toTransportConfig())
                .send(partition0, new DefaultQueueMessage("device-1", "device-1".getBytes(StandardCharsets.UTF_8),
                        Map.of(QueueMessage.SOURCE_PARTITION_HEADER, "3".getBytes(StandardCharsets.UTF_8))),
                        QueueCallback.EMPTY);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch processed = new CountDownLatch(1);
        consumerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "dlq-consumer"));
        workerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "dlq-worker"));

        manager = QueueConsumerManager.<QueueMessage>builder()
                .queueKey("Main")
                .config(definition.toTransportConfig())
                .consumerCreator((cfg, tpi) -> new InMemoryQueueConsumer(storage, cfg, "cg-dlq"))
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(QueueMsgPackPipeline.create(
                        definition.toTransportConfig(),
                        (id, message) -> {
                            processed.countDown();
                            attempts.incrementAndGet();
                            return CompletableFuture.failedFuture(new IllegalStateException("always fail"));
                        },
                        deadLetterPublisher)::apply)
                .build();
        manager.update(Set.of(partition0));

        assertTrue(processed.await(5, TimeUnit.SECONDS));
        awaitCommitted(storage, queueId, "cg-dlq", partition0.getFullTopicName());

        InMemoryQueueConsumer mainRecovery = new InMemoryQueueConsumer(
                storage, definition.toTransportConfig(), "cg-dlq");
        mainRecovery.subscribe(Set.of(partition0));
        assertEquals(List.of(), mainRecovery.poll(50).stream().map(QueueMessage::getKey).toList());

        String dlqTopic = DeadLetterTopics.resolve(topic);
        assertTrue(admin.contains(dlqTopic));

        InMemoryQueueConsumer dlqReader = new InMemoryQueueConsumer(
                storage, dlqConfig(definition, dlqTopic), "dlq-reader");
        dlqReader.subscribe(Set.of(new TopicPartitionInfo(dlqTopic, null, null, true)));
        List<QueueMessage> dlqMessages = dlqReader.poll(100);
        assertEquals(1, dlqMessages.size());

        DeadLetterEnvelope envelope = DeadLetterEnvelopeCodec.decode(dlqMessages.getFirst().getData());
        assertEquals("device-1", envelope.key());
        assertEquals(topic, envelope.sourceTopic());
        assertEquals(3, envelope.sourcePartition());
        assertEquals("always fail", envelope.failureMessage());
        assertEquals(new String(Base64.getDecoder().decode(envelope.dataBase64()), StandardCharsets.UTF_8), "device-1");
        assertEquals(3, attempts.get());
    }

    private static void awaitCommitted(InMemoryQueueStorage storage,
                                         UUID queueId,
                                         String consumerGroup,
                                         String fullTopic) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!storage.hasInFlight(queueId, consumerGroup, fullTopic)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("message still in-flight");
    }

    private static QueueDefinition definition(UUID id, String topic) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(2)
                .pollInterval(20)
                .consumerPerPartition(true)
                .packProcessingTimeout(5_000)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.RETRY_FAILED, 2, 0, 0, 0))
                .build();
    }

    private static com.roseboard.infrastructure.queue.config.QueueTransportConfig dlqConfig(
            QueueDefinition definition, String dlqTopic) {
        return new com.roseboard.infrastructure.queue.config.QueueTransportConfig(
                definition.id(),
                definition.tenantId(),
                definition.name(),
                dlqTopic,
                definition.pollInterval(),
                1,
                false,
                definition.packProcessingTimeout(),
                definition.submitStrategy(),
                definition.processingStrategy());
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }
}
