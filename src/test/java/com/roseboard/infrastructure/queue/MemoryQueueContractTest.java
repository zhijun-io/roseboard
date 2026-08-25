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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryQueueContractTest {

    @Test
    void sendPollCommitAndRestoreUncommittedBatch() {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = QueueDefinition.builder()
                .id(queueId)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic("mem.main")
                .partitions(1)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();

        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        InMemoryQueueProducer producer = new InMemoryQueueProducer(storage, definition.toTransportConfig());
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);

        producer.send(tpi, msg("1"), QueueCallback.EMPTY);
        producer.send(tpi, msg("2"), QueueCallback.EMPTY);
        producer.send(tpi, msg("3"), QueueCallback.EMPTY);

        InMemoryQueueConsumer consumer = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "group-a");
        consumer.subscribe(Set.of(tpi));

        List<QueueMessage> batch = consumer.poll(10);
        assertEquals(List.of("1", "2", "3"), batch.stream().map(QueueMessage::getKey).toList());

        List<QueueMessage> whileInFlight = consumer.poll(1);
        assertTrue(whileInFlight.isEmpty());

        consumer.restoreInFlight();
        List<QueueMessage> restored = consumer.poll(10);
        assertEquals(List.of("1", "2", "3"), restored.stream().map(QueueMessage::getKey).toList());

        consumer.commit();
        assertTrue(consumer.poll(5).isEmpty());
    }

    @Test
    void stopBlocksDeliveryAndCallbackIsExclusive() {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = QueueDefinition.builder()
                .id(queueId)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic("mem.stop")
                .partitions(1)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        InMemoryQueueProducer producer = new InMemoryQueueProducer(storage, definition.toTransportConfig());
        TopicPartitionInfo tpi = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        InMemoryQueueConsumer consumer = new InMemoryQueueConsumer(storage, definition.toTransportConfig(), "group-b");
        consumer.subscribe(Set.of(tpi));
        consumer.stop();
        consumer.stop();

        AtomicInteger success = new AtomicInteger();
        producer.send(tpi, msg("x"), QueueCallback.once(new QueueCallback() {
            @Override
            public void onSuccess() {
                success.incrementAndGet();
            }

            @Override
            public void onFailure(Throwable t) {
            }
        }));
        assertEquals(1, success.get());
        assertTrue(consumer.poll(5).isEmpty());

        consumer.unsubscribe();
        consumer.unsubscribe();
        assertTrue(storage.isProcessLocalOnly());
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }
}
