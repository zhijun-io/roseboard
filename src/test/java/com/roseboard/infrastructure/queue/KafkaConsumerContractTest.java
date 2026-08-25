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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConsumerContractTest {

    private static KafkaContainer kafka;
    private static KafkaQueueProvider provider;

    @BeforeAll
    static void start() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
        kafka.start();
        KafkaQueueProperties properties = new KafkaQueueProperties()
                .bootstrapServers(kafka.getBootstrapServers())
                .replicationFactor((short) 1);
        provider = new KafkaQueueProvider(properties);
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
    void enableAutoCommitIsForcedFalse() {
        Properties props = provider.properties().toConsumerProps("t");
        assertEquals(false, props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void groupSubscribeSuffixedTopicPauseHeartbeatCommitResumeAndRedelivery() throws Exception {
        UUID queueId = UUID.randomUUID();
        String baseTopic = "rb.cons.suffix";
        QueueDefinition definition = definition(queueId, baseTopic, 1);
        TopicPartitionInfo tpi = new TopicPartitionInfo(baseTopic, null, 0, false, true);

        KafkaQueueProducer producer = (KafkaQueueProducer) provider.createProducer(definition.toTransportConfig());
        CountDownLatch sent = new CountDownLatch(1);
        producer.send(tpi, msg("m1"), new QueueCallback() {
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

        KafkaQueueConsumer consumer = (KafkaQueueConsumer) provider.createConsumer(definition.toTransportConfig(), "cg-suffix");
        consumer.subscribe(Set.of(tpi));

        List<QueueMessage> batch = pollUntil(consumer, 20_000);
        assertEquals(List.of("m1"), batch.stream().map(QueueMessage::getKey).toList());

        // While in-flight (paused), further polls are heartbeat-only — no new delivery.
        assertTrue(consumer.poll(200).isEmpty());

        // Stop without commit → restart redelivers.
        consumer.stop();
        consumer.unsubscribe();

        KafkaQueueConsumer recovery = (KafkaQueueConsumer) provider.createConsumer(definition.toTransportConfig(), "cg-suffix");
        recovery.subscribe(Set.of(tpi));
        List<QueueMessage> again = pollUntil(recovery, 20_000);
        assertEquals(List.of("m1"), again.stream().map(QueueMessage::getKey).toList());
        recovery.commit();
        assertTrue(recovery.poll(300).isEmpty());
        recovery.unsubscribe();
        producer.stop();
    }

    @Test
    void manualNativePartitionAssignment() throws Exception {
        UUID queueId = UUID.randomUUID();
        String topic = "rb.cons.native";
        QueueDefinition definition = definition(queueId, topic, 2);
        TopicPartitionInfo tpi = new TopicPartitionInfo(topic, null, 1, true, true);

        provider.adminForPartitions(2).createTopicIfNotExists(topic, "partitions=2", true);
        KafkaQueueProducer producer = (KafkaQueueProducer) provider.createProducer(definition.toTransportConfig());
        CountDownLatch sent = new CountDownLatch(1);
        producer.send(tpi, msg("n1"), new QueueCallback() {
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

        // Manual assign path: useInternalPartition with explicit partition (no group subscribe).
        KafkaQueueConsumer consumer = new KafkaQueueConsumer(
                provider.properties(), topic, "native-client", null, provider.adminForPartitions(2));
        consumer.subscribe(Set.of(tpi));
        List<QueueMessage> batch = pollUntil(consumer, 20_000);
        assertEquals(List.of("n1"), batch.stream().map(QueueMessage::getKey).toList());
        // No groupId → commit is a no-op for offsets, but resume still clears pause.
        consumer.commit();
        assertTrue(consumer.poll(200).isEmpty());
        consumer.unsubscribe();
        producer.stop();
    }

    private static List<QueueMessage> pollUntil(KafkaQueueConsumer consumer, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<QueueMessage> batch = consumer.poll(500);
            if (!batch.isEmpty()) {
                return batch;
            }
        }
        return List.of();
    }

    private static QueueDefinition definition(UUID id, String topic, int partitions) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(partitions)
                .pollInterval(25)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }
}
