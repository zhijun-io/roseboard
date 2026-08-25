package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProducer;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProperties;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProvider;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaFailureAndRebalanceTest {

    private static KafkaContainer kafka;
    private static KafkaQueueProvider provider;

    @BeforeAll
    static void start() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
        kafka.start();
        provider = new KafkaQueueProvider(new KafkaQueueProperties()
                .bootstrapServers(kafka.getBootstrapServers())
                .replicationFactor((short) 1));
    }

    @AfterAll
    static void stop() {
        if (provider != null) {
            provider.destroy();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void commitFailureMarksFailedAndClosesWithoutAnotherCommit() {
        FakeOps fake = new FakeOps();
        fake.nextRecords = oneRecord("t-fail", 0, "k");
        KafkaQueueConsumer consumer = new KafkaQueueConsumer(
                provider.properties(), "t-fail", "c2", "g2", provider.adminForPartitions(1), fake);
        consumer.subscribe(Set.of(new TopicPartitionInfo("t-fail", null, 0, false, true)));
        assertEquals(1, consumer.poll(100).size());
        fake.failCommit = true;
        assertThrows(IllegalStateException.class, consumer::commit);
        assertEquals(KafkaQueueConsumer.ConsumerState.FAILED, consumer.state());
        assertEquals(1, fake.commitSyncCalls.get());
        assertTrue(fake.closed.get());
    }

    @Test
    void staleCompletionAfterRevokeDoesNotCommit() {
        FakeOps fake = new FakeOps();
        fake.nextRecords = oneRecord("t-rev", 0, "k");
        KafkaQueueConsumer consumer = new KafkaQueueConsumer(
                provider.properties(), "t-rev", "c3", "g3", provider.adminForPartitions(1), fake);
        consumer.subscribe(Set.of(new TopicPartitionInfo("t-rev", null, 0, false, true)));
        assertEquals(1, consumer.poll(50).size());
        int gen = consumer.assignmentGeneration();
        consumer.onPartitionsRevoked(List.of(new TopicPartition("t-rev", 0)));
        consumer.commit(gen);
        assertEquals(0, fake.commitSyncCalls.get());
    }

    @Test
    void groupRebalanceAllowsWholeBatchReplay() throws Exception {
        UUID queueId = UUID.randomUUID();
        String topic = "rb.rebalance." + UUID.randomUUID();
        QueueDefinition definition = QueueDefinition.builder()
                .id(queueId)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(1)
                .pollInterval(25)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
        TopicPartitionInfo tpi = new TopicPartitionInfo(topic, null, 0, false, true);

        KafkaQueueProducer producer = (KafkaQueueProducer) provider.createProducer(definition.toTransportConfig());
        CountDownLatch sent = new CountDownLatch(1);
        producer.send(tpi, new DefaultQueueMessage("r1", "r1".getBytes(StandardCharsets.UTF_8), null),
                new QueueCallback() {
                    @Override
                    public void onSuccess() {
                        sent.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        sent.countDown();
                    }
                });
        producer.flush();
        assertTrue(sent.await(20, TimeUnit.SECONDS));

        String group = "cg-reb-" + UUID.randomUUID();
        KafkaQueueConsumer first = (KafkaQueueConsumer) provider.createConsumer(definition.toTransportConfig(), group);
        first.subscribe(Set.of(tpi));
        List<QueueMessage> batch = pollUntil(first, 20_000);
        assertEquals(List.of("r1"), batch.stream().map(QueueMessage::getKey).toList());
        first.unsubscribe();

        KafkaQueueConsumer second = (KafkaQueueConsumer) provider.createConsumer(definition.toTransportConfig(), group);
        second.subscribe(Set.of(tpi));
        List<QueueMessage> replay = pollUntil(second, 20_000);
        assertEquals(List.of("r1"), replay.stream().map(QueueMessage::getKey).toList());
        second.commit();
        second.unsubscribe();
        producer.stop();
    }

    private static ConsumerRecords<String, byte[]> oneRecord(String topic, int partition, String key) {
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(topic, partition, 0L, key, new byte[] {1});
        return new ConsumerRecords<>(Map.of(new TopicPartition(topic, partition), List.of(record)), Map.of());
    }

    private static List<QueueMessage> pollUntil(KafkaQueueConsumer consumer, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<QueueMessage> batch = consumer.poll(400);
            if (!batch.isEmpty()) {
                return batch;
            }
        }
        return List.of();
    }

    private static final class FakeOps implements KafkaQueueConsumer.ConsumerOps {
        private ConsumerRecords<String, byte[]> nextRecords = ConsumerRecords.empty();
        private boolean failCommit;
        private final AtomicInteger commitSyncCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<TopicPartition> paused = new HashSet<>();

        @Override
        public void assign(Collection<TopicPartition> partitions) {
        }

        @Override
        public void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
        }

        @Override
        public void unsubscribe() {
        }

        @Override
        public ConsumerRecords<String, byte[]> poll(Duration timeout) {
            ConsumerRecords<String, byte[]> out = nextRecords;
            nextRecords = ConsumerRecords.empty();
            return out;
        }

        @Override
        public void commitSync() {
            commitSyncCalls.incrementAndGet();
            if (failCommit) {
                throw new IllegalStateException("commit boom");
            }
        }

        @Override
        public void pause(Collection<TopicPartition> partitions) {
            paused.addAll(partitions);
        }

        @Override
        public void resume(Collection<TopicPartition> partitions) {
            paused.removeAll(partitions);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
