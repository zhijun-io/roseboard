package com.roseboard.infrastructure.queue.processing;

import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.deadletter.DeadLetterPublisher;
import com.roseboard.infrastructure.queue.spi.QueueMessageHandler;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ProcessingStrategy dispatch for queue pack handlers.
 */
public final class ProcessingStrategyExecutor {

    private ProcessingStrategyExecutor() {
    }

    public static void requireSupported(ProcessingStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(strategy.type(), "type");
        if (strategy.retries() < 0) {
            throw new IllegalArgumentException("processingStrategy.retries must be non-negative");
        }
        if (strategy.failurePercentage() < 0 || strategy.failurePercentage() > 100) {
            throw new IllegalArgumentException("processingStrategy.failurePercentage must be between 0 and 100");
        }
        if (strategy.pauseBetweenRetries() < 0 || strategy.maxPauseBetweenRetries() < 0) {
            throw new IllegalArgumentException("processingStrategy retry pauses must be non-negative");
        }
    }

    public static <M extends QueueMessage> CompletionStage<Void> execute(
            ProcessingStrategy strategy,
            List<M> batch,
            QueueMessageHandler<M> messageHandler) {
        return execute(strategy, batch, messageHandler, null);
    }

    public static <M extends QueueMessage> CompletionStage<Void> execute(
            ProcessingStrategy strategy,
            List<M> batch,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher) {
        return execute(strategy, batch, messageHandler, deadLetterPublisher, 0);
    }

    public static <M extends QueueMessage> CompletionStage<Void> execute(
            ProcessingStrategy strategy,
            List<M> batch,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher,
            long timeoutMs) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(messageHandler, "messageHandler");
        requireSupported(strategy);
        if (batch == null || batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return switch (strategy.type()) {
            case SKIP_ALL_FAILURES, SKIP_ALL_FAILURES_AND_TIMED_OUT ->
                    processSkip(batch, messageHandler, deadLetterPublisher, timeoutMs);
            case RETRY_ALL, RETRY_FAILED, RETRY_TIMED_OUT, RETRY_FAILED_AND_TIMED_OUT ->
                    processRetry(
                            strategy, messageHandler, batch, deadLetterPublisher, timeoutMs);
        };
    }

    private static <M extends QueueMessage> CompletionStage<Void> processSkip(
            List<M> messages,
            QueueMessageHandler<M> messageHandler,
            DeadLetterPublisher deadLetterPublisher,
            long timeoutMs) {
        List<AttemptHandle<M>> attempts = messages.stream()
                .map(message -> attempt(messageHandler, message))
                .toList();
        return awaitRound(attempts, messages, timeoutMs)
                .thenCompose(results -> publishFailures(
                        results.stream().filter(Attempt::failed).toList(), deadLetterPublisher));
    }

    private static <M extends QueueMessage> CompletionStage<Void> processRetry(
            ProcessingStrategy strategy,
            QueueMessageHandler<M> messageHandler,
            List<M> batch,
            DeadLetterPublisher deadLetterPublisher,
            long timeoutMs) {
        return processRetryRound(
                strategy, messageHandler, batch, batch.size(), 0,
                strategy.pauseBetweenRetries(), deadLetterPublisher, timeoutMs);
    }

    private static <M extends QueueMessage> CompletionStage<Void> processRetryRound(
            ProcessingStrategy strategy,
            QueueMessageHandler<M> messageHandler,
            List<M> messages,
            int totalMessages,
            int failedAttempts,
            long pauseSeconds,
            DeadLetterPublisher deadLetterPublisher,
            long timeoutMs) {
        List<AttemptHandle<M>> attempts = messages.stream()
                .map(message -> attempt(messageHandler, message))
                .toList();
        return awaitRound(attempts, messages, timeoutMs)
                .thenCompose(results -> {
                    List<Attempt<M>> failures = results.stream()
                            .filter(Attempt::failed)
                            .toList();
                    if (failures.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    int nextFailedAttempts = failedAttempts + 1;
                    List<Attempt<M>> retryable = failures.stream()
                            .filter(attempt -> shouldRetry(strategy, attempt))
                            .toList();
                    boolean exhausted = nextFailedAttempts > strategy.retries()
                            || exceedsFailurePercentage(failures.size(), totalMessages,
                            strategy.failurePercentage());
                    if (exhausted || retryable.isEmpty()) {
                        return publishFailures(failures, deadLetterPublisher);
                    }
                    List<M> failedMessages = strategy.type() == ProcessingStrategyType.RETRY_ALL
                            ? messages
                            : retryable.stream().map(Attempt::message).toList();
                    return pause(pauseSeconds).thenCompose(ignoredPause -> processRetryRound(
                            strategy, messageHandler, failedMessages, totalMessages,
                            nextFailedAttempts, nextPause(strategy, pauseSeconds),
                            deadLetterPublisher, timeoutMs));
                });
    }

    private static <M extends QueueMessage> CompletionStage<List<Attempt<M>>> awaitRound(
            List<AttemptHandle<M>> attempts,
            List<M> messages,
            long timeoutMs) {
        CompletableFuture<Void> all = CompletableFuture.allOf(
                attempts.stream().map(AttemptHandle::result).toArray(CompletableFuture[]::new));
        if (timeoutMs <= 0) {
            return all.thenApply(ignored -> attempts.stream()
                    .map(AttemptHandle::join)
                    .toList());
        }
        CompletableFuture<Void> completed = new CompletableFuture<>();
        all.whenComplete((ignored, error) -> completed.complete(null));
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
                .execute(() -> completed.complete(null));
        return completed.thenApply(ignored -> {
            List<Attempt<M>> results = new java.util.ArrayList<>(messages.size());
            for (int i = 0; i < attempts.size(); i++) {
                AttemptHandle<M> attempt = attempts.get(i);
                if (attempt.result().isDone()) {
                    results.add(attempt.join());
                } else {
                    attempt.cancelHandler();
                    results.add(Attempt.timedOut(messages.get(i)));
                }
            }
            return results;
        });
    }

    private static boolean shouldRetry(ProcessingStrategy strategy, Attempt<?> attempt) {
        return switch (strategy.type()) {
            case RETRY_ALL -> !attempt.timedOut() || attempt.retrySafe();
            case RETRY_FAILED -> !attempt.timedOut();
            case RETRY_TIMED_OUT -> attempt.timedOut() && attempt.retrySafe();
            case RETRY_FAILED_AND_TIMED_OUT -> !attempt.timedOut() || attempt.retrySafe();
            default -> false;
        };
    }

    private static boolean exceedsFailurePercentage(int failures, int total, double percentage) {
        return percentage > 0 && failures * 100.0 / total > percentage;
    }

    private static <M extends QueueMessage> CompletionStage<Void> publishFailures(
            List<Attempt<M>> failures, DeadLetterPublisher deadLetterPublisher) {
        if (deadLetterPublisher == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] publications = failures.stream()
                .map(attempt -> deadLetterPublisher.publish(
                        attempt.message(), sourcePartition(attempt.message()), attempt.error()).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(publications);
    }

    private record Attempt<M extends QueueMessage>(M message, Throwable error, boolean retrySafe) {
        private static <M extends QueueMessage> Attempt<M> timedOut(M message) {
            // CompletionStage does not guarantee that cancellation stops the underlying handler.
            // A timed-out attempt is therefore terminal; retrying it could run side effects twice.
            return new Attempt<>(message, new TimeoutException("queue pack processing timed out"), false);
        }

        private boolean failed() {
            return error != null;
        }

        private boolean timedOut() {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause instanceof TimeoutException;
        }
    }

    private record AttemptHandle<M extends QueueMessage>(
            CompletableFuture<Attempt<M>> result, CompletionStage<Void> handler) {
        private static <M extends QueueMessage> AttemptHandle<M> create(
                CompletionStage<Void> handler, CompletableFuture<Attempt<M>> result) {
            return new AttemptHandle<>(result, handler);
        }

        private Attempt<M> join() {
            return result.join();
        }

        private void cancelHandler() {
            if (handler instanceof CompletableFuture<?> future) {
                future.cancel(true);
            }
        }
    }

    private static <M extends QueueMessage> AttemptHandle<M> attempt(
            QueueMessageHandler<M> messageHandler,
            M message) {
        try {
            CompletionStage<Void> handler = messageHandler.handle(UUID.randomUUID(), message);
            CompletableFuture<Attempt<M>> result = handler.handle(
                    (ignored, error) -> new Attempt<>(message, error, false)).toCompletableFuture();
            return AttemptHandle.create(handler, result);
        } catch (Throwable error) {
            return AttemptHandle.create(
                    CompletableFuture.completedFuture(null),
                    CompletableFuture.completedFuture(new Attempt<>(message, error, true)));
        }
    }

    private static Integer sourcePartition(QueueMessage message) {
        byte[] value = message.getHeaders() == null
                ? null : message.getHeaders().get(QueueMessage.SOURCE_PARTITION_HEADER);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(new String(value, java.nio.charset.StandardCharsets.UTF_8));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long nextPause(ProcessingStrategy strategy, long pauseSeconds) {
        if (pauseSeconds <= 0) {
            return 0;
        }
        long doubled = pauseSeconds > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : pauseSeconds * 2;
        return strategy.maxPauseBetweenRetries() > 0
                ? Math.min(doubled, strategy.maxPauseBetweenRetries())
                : doubled;
    }

    private static CompletionStage<Void> pause(long pauseSeconds) {
        if (pauseSeconds <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(
                        TimeUnit.SECONDS.toMillis(pauseSeconds), TimeUnit.MILLISECONDS)
                .execute(() -> delayed.complete(null));
        return delayed;
    }
}
