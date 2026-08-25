package com.roseboard.infrastructure.queue.processing;

import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.deadletter.DeadLetterPublisher;
import com.roseboard.infrastructure.queue.spi.QueueMessageHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * BURST submit + skip-failures processing for bound queue consumers.
 */
public final class QueueMsgPackPipeline {

    private QueueMsgPackPipeline() {
    }

    public static <M extends QueueMessage> Function<List<M>, CompletionStage<Void>> create(
            QueueTransportConfig config,
            QueueMessageHandler<M> messageHandler) {
        return create(config, messageHandler, null);
    }

    public static <M extends QueueMessage> Function<List<M>, CompletionStage<Void>> create(
            QueueTransportConfig config,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageHandler, "messageHandler");
        requireSupportedConfig(config);
        return batch -> processSubmitStrategy(
                config, batch, messageHandler, deadLetterPublisher);
    }

    public static void requireSupportedConfig(QueueTransportConfig config) {
        ProcessingStrategyExecutor.requireSupported(config.processingStrategy());
        requireSupportedSubmit(config.submitStrategy());
    }

    static <M extends QueueMessage> CompletionStage<Void> processBurst(
            QueueMessageHandler<M> messageHandler,
            List<M> batch) {
        return process(messageHandler, batch, 0);
    }

    static <M extends QueueMessage> CompletionStage<Void> processBurst(
            QueueMessageHandler<M> messageHandler,
            List<M> batch,
            long timeoutMs) {
        return process(messageHandler, batch, timeoutMs);
    }

    static void requireSupportedSubmit(SubmitStrategy submitStrategy) {
        Objects.requireNonNull(submitStrategy, "submitStrategy");
        SubmitStrategyType type = Objects.requireNonNull(submitStrategy.type(), "type");
        if (type == SubmitStrategyType.BATCH && submitStrategy.batchSize() < 1) {
            throw new IllegalArgumentException("BATCH submitStrategy.batchSize must be at least 1");
        }
    }

    private static <M extends QueueMessage> CompletionStage<Void> processSubmitStrategy(
            QueueTransportConfig config,
            List<M> batch,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher) {
        return switch (config.submitStrategy().type()) {
            case BURST, BATCH -> config.submitStrategy().type() == SubmitStrategyType.BATCH
                    ? processBatch(config, batch, messageHandler, deadLetterPublisher)
                    : ProcessingStrategyExecutor.execute(
                    config.processingStrategy(), batch, messageHandler, deadLetterPublisher,
                    config.packProcessingTimeout());
            case SEQUENTIAL_BY_ORIGINATOR -> processSequentially(
                    config, batch, messageHandler, deadLetterPublisher,
                    message -> message.getKey());
            case SEQUENTIAL_BY_TENANT -> processSequentially(
                    config, batch, messageHandler, deadLetterPublisher,
                    message -> header(message, "tenantId", message.getKey()));
            case SEQUENTIAL -> processSequentially(
                    config, batch, messageHandler, deadLetterPublisher,
                    ignored -> "all");
        };
    }

    private static <M extends QueueMessage> CompletionStage<Void> processSequentially(
            QueueTransportConfig config,
            List<M> messages,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher,
            Function<M, String> groupKey) {
        Map<String, List<M>> groups = new LinkedHashMap<>();
        for (M message : messages) {
            groups.computeIfAbsent(groupKey.apply(message), ignored -> new ArrayList<>()).add(message);
        }
        CompletableFuture<?>[] stages = groups.values().stream()
                .map(group -> processOriginatorSequentially(
                        config, group, messageHandler, deadLetterPublisher).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(stages);
    }

    private static String header(QueueMessage message, String key, String fallback) {
        byte[] value = message.getHeaders() == null ? null : message.getHeaders().get(key);
        return value == null ? fallback : new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static <M extends QueueMessage> CompletionStage<Void> processBatch(
            QueueTransportConfig config,
            List<M> messages,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher) {
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        int batchSize = config.submitStrategy().batchSize();
        for (int start = 0; start < messages.size(); start += batchSize) {
            List<M> chunk = messages.subList(start, Math.min(start + batchSize, messages.size()));
            chain = chain.thenCompose(ignored -> ProcessingStrategyExecutor.execute(
                    config.processingStrategy(), chunk, messageHandler, deadLetterPublisher,
                    config.packProcessingTimeout()));
        }
        return chain;
    }

    private static <M extends QueueMessage> CompletionStage<Void> processOriginatorSequentially(
            QueueTransportConfig config,
            List<M> messages,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher) {
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (M message : messages) {
            chain = chain.thenCompose(ignored -> ProcessingStrategyExecutor.execute(
                    config.processingStrategy(), List.of(message), messageHandler,
                    deadLetterPublisher, config.packProcessingTimeout()));
        }
        return chain;
    }

    static <M extends QueueMessage> CompletionStage<Void> process(
            QueueMessageHandler<M> messageHandler,
            List<M> batch) {
        return process(messageHandler, batch, 0);
    }

    private static <M extends QueueMessage> CompletionStage<Void> process(
            QueueMessageHandler<M> messageHandler,
            List<M> batch,
            long timeoutMs) {
        if (batch == null || batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] stages = batch.stream()
                .map(message -> handleIgnoringFailure(messageHandler, message))
                .toArray(CompletableFuture[]::new);
        CompletionStage<Void> completed = CompletableFuture.allOf(stages);
        if (timeoutMs > 0) {
            completed = completed.toCompletableFuture()
                    .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(ignored -> null);
        }
        return completed;
    }

    private static <M extends QueueMessage> CompletableFuture<Void> handleIgnoringFailure(
            QueueMessageHandler<M> messageHandler,
            M message) {
        try {
            return messageHandler.handle(UUID.randomUUID(), message)
                    .exceptionally(ignored -> null)
                    .toCompletableFuture();
        } catch (Throwable ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
