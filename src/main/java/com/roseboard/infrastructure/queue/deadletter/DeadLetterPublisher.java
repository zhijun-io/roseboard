package com.roseboard.infrastructure.queue.deadletter;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DeadLetterPublisher {
    private final QueueProducer<QueueMessage> producer;
    private final String sourceTopic;
    private final TopicPartitionInfo dlqPartition;

    private DeadLetterPublisher(QueueProducer<QueueMessage> producer, String sourceTopic, String dlqTopic) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.sourceTopic = Objects.requireNonNull(sourceTopic, "sourceTopic");
        this.dlqPartition = new TopicPartitionInfo(dlqTopic, null, null, true);
    }

    public static DeadLetterPublisher create(QueueProducerProvider producerProvider,
                                           QueueAdmin admin,
                                           QueueTransportConfig config) {
        Objects.requireNonNull(producerProvider, "producerProvider");
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(config, "config");
        String dlqTopic = DeadLetterTopics.resolve(config.topic());
        admin.createTopicIfNotExists(dlqTopic);
        QueueTransportConfig dlqConfig = new QueueTransportConfig(
                config.id(),
                config.tenantId(),
                config.name(),
                dlqTopic,
                config.pollInterval(),
                1,
                false,
                config.packProcessingTimeout(),
                config.submitStrategy(),
                config.processingStrategy());
        return new DeadLetterPublisher(producerProvider.createProducer(dlqConfig), config.topic(), dlqTopic);
    }

    public CompletionStage<Void> publish(QueueMessage message,
                                         Integer sourcePartition,
                                         Throwable error) {
        Objects.requireNonNull(message, "message");
        String originalTopic = header(message, QueueMessage.SOURCE_TOPIC_HEADER, sourceTopic);
        Integer originalPartition = sourcePartition == null
                ? headerInteger(message, QueueMessage.SOURCE_PARTITION_HEADER) : sourcePartition;
        byte[] payload = DeadLetterEnvelopeCodec.encode(
                message, originalTopic, originalPartition, unwrap(error));
        QueueMessage dlqMessage = new DefaultQueueMessage(message.getKey(), payload, null);
        CompletableFuture<Void> published = new CompletableFuture<>();
        producer.send(dlqPartition, dlqMessage, QueueCallback.once(new QueueCallback() {
            @Override
            public void onSuccess() {
                published.complete(null);
            }

            @Override
            public void onFailure(Throwable t) {
                published.completeExceptionally(t);
            }
        }));
        return published;
    }

    String dlqTopic() {
        return dlqPartition.getTopic();
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static String header(QueueMessage message, String key, String fallback) {
        byte[] value = message.getHeaders() == null ? null : message.getHeaders().get(key);
        return value == null ? fallback : new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Integer headerInteger(QueueMessage message, String key) {
        String value = header(message, key, null);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
