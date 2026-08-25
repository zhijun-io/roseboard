package com.roseboard.infrastructure.queue.processing;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.adapter.memory.MemoryQueueAdmin;
import com.roseboard.infrastructure.queue.deadletter.DeadLetterPublisher;
import com.roseboard.queue.QueueDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingStrategyExecutorRetryTest {

    @Test
    void retryFailedSucceedsOnThirdAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_FAILED, 2, 0, 0, 0);

        ProcessingStrategyExecutor.execute(strategy, List.of(msg("device-1")), (id, message) -> {
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(new IllegalStateException("fail"));
            }
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();

        assertEquals(3, attempts.get());
    }

    @Test
    void retryFailedExhaustedDoesNotBlockPack() {
        AtomicInteger attempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_FAILED, 2, 0, 0, 0);

        ProcessingStrategyExecutor.execute(strategy, List.of(msg("device-1")), (id, message) -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("always fail"));
        }).toCompletableFuture().join();

        assertEquals(3, attempts.get());
    }

    @Test
    void retryFailedSuccessfulMessagesStillCompletePack() {
        AtomicInteger okAttempts = new AtomicInteger();
        AtomicInteger failAttempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_FAILED, 1, 0, 0, 0);

        ProcessingStrategyExecutor.execute(
                strategy,
                List.of(msg("ok"), msg("fail")),
                (id, message) -> {
                    if ("ok".equals(message.getKey())) {
                        okAttempts.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                    failAttempts.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("fail"));
                }).toCompletableFuture().join();

        assertEquals(1, okAttempts.get());
        assertEquals(2, failAttempts.get());
    }

    @Test
    void retryFailedGrowsPauseUntilMaximum() {
        AtomicInteger attempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_FAILED, 2, 0, 1, 2);
        long started = System.nanoTime();

        ProcessingStrategyExecutor.execute(strategy, List.of(msg("device-1")), (id, message) -> {
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(new IllegalStateException("fail"));
            }
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();

        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        assertEquals(3, attempts.get());
        assertTrue(elapsedMs >= 2_600,
                "expected 1s then 2s retry pauses, elapsed=" + elapsedMs);
    }

    @Test
    void retryFailedStopsWhenPackFailurePercentageIsExceeded() {
        AtomicInteger failedAttempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_FAILED, 3, 25, 0, 0);

        ProcessingStrategyExecutor.execute(strategy, List.of(msg("ok"), msg("fail")), (id, message) -> {
            if ("fail".equals(message.getKey())) {
                failedAttempts.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("fail"));
            }
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();

        assertEquals(1, failedAttempts.get());
    }

    @Test
    void timedOutHandlersAreNotRetriedWhenCancellationCannotBeProven() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger timeoutAttempts = new AtomicInteger();
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_TIMED_OUT, 1, 0, 0, 0);

        ProcessingStrategyExecutor.execute(strategy, List.of(msg("timeout"), msg("ok")), (id, message) -> {
            attempts.incrementAndGet();
            if ("timeout".equals(message.getKey()) && timeoutAttempts.incrementAndGet() == 1) {
                return new CompletableFuture<>();
            }
            return CompletableFuture.completedFuture(null);
        }, null, 20).toCompletableFuture().join();

        assertEquals(2, attempts.get());
        assertEquals(1, timeoutAttempts.get());
    }

    @Test
    void timeoutDoesNotRetryWhileOriginalHandlerCanStillRun() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ProcessingStrategy strategy = new ProcessingStrategy(
                ProcessingStrategyType.RETRY_ALL, 1, 0, 0, 0);

        CompletionStage<Void> result = ProcessingStrategyExecutor.execute(
                strategy, List.of(msg("slow")), (id, message) -> {
                    if (attempts.incrementAndGet() == 1) {
                        CompletableFuture<Void> stage = new CompletableFuture<>();
                        CompletableFuture.runAsync(() -> {
                            int running = active.incrementAndGet();
                            maxActive.accumulateAndGet(running, Math::max);
                            firstStarted.countDown();
                            try {
                                releaseFirst.await();
                                stage.complete(null);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                active.decrementAndGet();
                            }
                        });
                        return stage;
                    }
                    int running = active.incrementAndGet();
                    maxActive.accumulateAndGet(running, Math::max);
                    active.decrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, null, 20);

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        result.toCompletableFuture().join();
        assertEquals(1, attempts.get());
        assertEquals(1, maxActive.get());
        releaseFirst.countDown();
    }

    @Test
    void retryFailedPublishesToDeadLetterAfterExhaustedRetries() {
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        InMemoryQueueProvider provider = new InMemoryQueueProvider(storage);
        MemoryQueueAdmin admin = new MemoryQueueAdmin();
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = QueueDefinition.builder()
                .id(queueId)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic("retry.dlq")
                .partitions(1)
                .submitStrategy(new com.roseboard.infrastructure.queue.config.SubmitStrategy(
                        com.roseboard.infrastructure.queue.config.SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(
                        ProcessingStrategyType.RETRY_FAILED, 1, 0, 0, 0))
                .build();
        DeadLetterPublisher publisher = DeadLetterPublisher.create(provider, admin, definition.toTransportConfig());
        AtomicInteger attempts = new AtomicInteger();

        ProcessingStrategyExecutor.execute(
                definition.processingStrategy(),
                List.of(new DefaultQueueMessage("k", new byte[] {1}, null)),
                (id, message) -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("nope"));
                },
                publisher).toCompletableFuture().join();

        assertEquals(2, attempts.get());
        assertTrue(admin.contains("retry.dlq.dlq"));
    }

    @Test
    void acceptsTbRetryProcessingStrategies() {
        for (ProcessingStrategyType type : List.of(
                ProcessingStrategyType.RETRY_ALL,
                ProcessingStrategyType.RETRY_FAILED,
                ProcessingStrategyType.RETRY_TIMED_OUT,
                ProcessingStrategyType.RETRY_FAILED_AND_TIMED_OUT)) {
            ProcessingStrategyExecutor.requireSupported(
                    new ProcessingStrategy(type, 0, 0, 0, 0));
        }
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }
}
