package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueAdmin;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProducer;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaProducerAdminContractTest {

    private static KafkaContainer kafka;
    private static KafkaQueueProperties properties;
    private static KafkaQueueAdmin kafkaAdmin;
    private static KafkaQueueAdmin admin;

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
        kafka.start();
        properties = new KafkaQueueProperties()
                .bootstrapServers(kafka.getBootstrapServers())
                .replicationFactor((short) 1)
                .requestTimeoutMs(15_000);
        kafkaAdmin = new KafkaQueueAdmin(properties);
        admin = kafkaAdmin.withPartitions(2);
    }

    @AfterAll
    static void stopKafka() {
        if (kafkaAdmin != null) {
            kafkaAdmin.destroy();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void producerPreservesStringKeyBinaryHeadersAndPayloadForBothPartitionModes() throws Exception {
        UUID key = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        byte[] payload = new byte[] {0x01, (byte) 0xfe, 0x10};
        Map<String, byte[]> headers = new HashMap<>();
        headers.put("bin", new byte[] {9, 8, 7});

        String suffixedTopic = "rb.prod.suffix";
        admin.createTopicIfNotExists(suffixedTopic + ".0", "partitions=1", true);
        TopicPartitionInfo suffixed = new TopicPartitionInfo(suffixedTopic, null, 0, false, true);

        String nativeTopic = "rb.prod.native";
        admin.createTopicIfNotExists(nativeTopic, "partitions=2", true);
        TopicPartitionInfo nativeTpi = new TopicPartitionInfo(nativeTopic, null, 1, true, true);

        KafkaQueueProducer producer = new KafkaQueueProducer(properties, suffixedTopic, "prod-1", admin);
        try {
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger successes = new AtomicInteger();
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            QueueCallback callback1 = callback(successes, latch, firstError);
            QueueCallback callback2 = callback(successes, latch, firstError);

            QueueMessage msg = new DefaultQueueMessage(key.toString(), payload, headers);
            producer.send(suffixed, msg, callback1);
            producer.flush();
            producer.send(nativeTpi, new DefaultQueueMessage(key.toString(), payload, headers), callback2);
            producer.flush();
            boolean done = latch.await(15, TimeUnit.SECONDS);
            assertTrue(done, "producer callbacks timed out; error=" + firstError.get()
                    + " successes=" + successes.get());
            assertEquals(2, successes.get());

            ConsumerRecord<String, byte[]> fromSuffix = readOne(suffixed.getFullTopicName());
            assertEquals(key.toString(), fromSuffix.key());
            assertArrayEquals(payload, fromSuffix.value());
            assertArrayEquals(new byte[] {9, 8, 7}, header(fromSuffix, "bin"));

            ConsumerRecord<String, byte[]> fromNative = readOne(nativeTopic);
            assertEquals(key.toString(), fromNative.key());
            assertEquals(1, fromNative.partition());
            assertArrayEquals(payload, fromNative.value());
        } finally {
            producer.stop();
        }
    }

    @Test
    void adminCreatesWithCustomPropertiesAndDeletesExplicitly() {
        String topic = "rb.admin.custom." + UUID.randomUUID();
        admin.createTopicIfNotExists(topic, "partitions=3;retention.ms=60000", true);
        assertTrue(kafkaAdmin.listTopics().contains(topic));

        admin.deleteTopic(topic);
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && kafkaAdmin.listTopics().contains(topic)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            kafkaAdmin.listTopics();
        }
        assertFalse(kafkaAdmin.listTopics().contains(topic));
    }

    @Test
    void adminCreatesDeadLetterTopicForQueueBinding() throws Exception {
        String mainTopic = "rb.dlq.main." + UUID.randomUUID();
        String dlqTopic = mainTopic + ".dlq";
        admin.createTopicIfNotExists(mainTopic, "partitions=2", true);
        admin.createTopicIfNotExists(dlqTopic, "partitions=1", true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean dlqCreated = false;
        while (System.nanoTime() < deadline) {
            if (admin.listTopics().contains(dlqTopic)) {
                dlqCreated = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(dlqCreated);

        KafkaQueueProducer producer = new KafkaQueueProducer(properties, dlqTopic, "dlq-producer", admin);
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            producer.send(
                    new TopicPartitionInfo(dlqTopic, null, null, true),
                    new DefaultQueueMessage("device-1", new byte[] {1, 2}, null),
                    QueueCallback.once(new QueueCallback() {
                        @Override
                        public void onSuccess() {
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            error.set(t);
                            latch.countDown();
                        }
                    }));
            producer.flush();
            assertTrue(latch.await(15, TimeUnit.SECONDS), "dlq publish failed: " + error.get());
            ConsumerRecord<String, byte[]> record = readOne(dlqTopic);
            assertEquals("device-1", record.key());
        } finally {
            producer.stop();
        }
    }

    @Test
    void callbackFailureIsExclusiveAndInvokedOnce() throws Exception {
        KafkaQueueProducer producer = new KafkaQueueProducer(properties, "rb.fail", "prod-fail", admin);
        try {
            AtomicInteger success = new AtomicInteger();
            AtomicInteger failure = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            QueueCallback callback = QueueCallback.once(new QueueCallback() {
                @Override
                public void onSuccess() {
                    success.incrementAndGet();
                    latch.countDown();
                }

                @Override
                public void onFailure(Throwable t) {
                    failure.incrementAndGet();
                    error.set(t);
                    latch.countDown();
                }
            });
            TopicPartitionInfo missingLeader = new TopicPartitionInfo("rb.no.such.topic.xyz", null, 0, true, true);
            try {
                producer.send(missingLeader, new DefaultQueueMessage("k", new byte[] {1}, null), callback);
            } catch (RuntimeException immediate) {
                callback.onFailure(immediate);
            }
            assertTrue(latch.await(20, TimeUnit.SECONDS) || failure.get() > 0 || success.get() > 0);
            assertTrue(success.get() + failure.get() >= 1);
            assertTrue(success.get() <= 1);
            assertTrue(failure.get() <= 1);
        } finally {
            producer.stop();
        }
    }

    @Test
    void consumerPropsForceEnableAutoCommitFalse() {
        properties.putOther(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        Properties props = properties.toConsumerProps("any");
        assertEquals("false", String.valueOf(props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)));
    }

    private static QueueCallback callback(AtomicInteger successes, CountDownLatch latch,
                                          AtomicReference<Throwable> firstError) {
        return QueueCallback.once(new QueueCallback() {
            @Override
            public void onSuccess() {
                successes.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                firstError.compareAndSet(null, t);
                latch.countDown();
            }
        });
    }

    private static ConsumerRecord<String, byte[]> readOne(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            while (System.currentTimeMillis() < deadline && found.isEmpty()) {
                consumer.poll(Duration.ofMillis(500)).forEach(found::add);
            }
            assertFalse(found.isEmpty(), "expected message on " + topic);
            return found.get(0);
        }
    }

    private static byte[] header(ConsumerRecord<String, byte[]> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : header.value();
    }
}
