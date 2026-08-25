package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.processing.QueueMsgPackPipeline;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueueMsgPackPipelineTest {

    @Test
    void processesBurstBatchThroughMessageHandler() {
        AtomicInteger handled = new AtomicInteger();
        QueueDefinition definition = definition(SubmitStrategyType.BURST, ProcessingStrategyType.SKIP_ALL_FAILURES);

        QueueMsgPackPipeline
                .create(definition.toTransportConfig(), (id, msg) -> {
                    handled.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })
                .apply(List.of(msg("a"), msg("b")))
                .toCompletableFuture()
                .join();

        assertEquals(2, handled.get());
    }

    @Test
    void splitsBatchStrategyIntoConfiguredChunks() {
        QueueDefinition definition = definition(SubmitStrategyType.BATCH, ProcessingStrategyType.SKIP_ALL_FAILURES);
        definition = definition.toBuilder()
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BATCH, 2))
                .build();
        AtomicInteger handled = new AtomicInteger();

        QueueMsgPackPipeline.create(definition.toTransportConfig(), (id, msg) -> {
                    handled.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                })
                .apply(List.of(msg("a"), msg("b"), msg("c")))
                .toCompletableFuture()
                .join();

        assertEquals(3, handled.get());
    }

    @Test
    void acceptsRetryFailedProcessingStrategy() {
        QueueDefinition definition = definition(SubmitStrategyType.BURST, ProcessingStrategyType.RETRY_FAILED);

        QueueMsgPackPipeline.create(definition.toTransportConfig(), (id, msg) ->
                CompletableFuture.completedFuture(null));
    }

    @Test
    void acceptsTbRetryProcessingStrategies() {
        QueueDefinition definition = definition(SubmitStrategyType.BURST, ProcessingStrategyType.RETRY_ALL);

        QueueMsgPackPipeline.create(definition.toTransportConfig(), (id, msg) ->
                CompletableFuture.completedFuture(null));
    }

    @Test
    void skipTimedOutCompletesAfterPackTimeout() {
        QueueDefinition definition = definition(
                SubmitStrategyType.BURST, ProcessingStrategyType.SKIP_ALL_FAILURES_AND_TIMED_OUT)
                .toBuilder()
                .packProcessingTimeout(20)
                .build();

        QueueMsgPackPipeline.create(definition.toTransportConfig(), (id, msg) ->
                        new CompletableFuture<>())
                .apply(List.of(msg("timeout")))
                .toCompletableFuture()
                .join();
    }

    private static QueueDefinition definition(SubmitStrategyType submit, ProcessingStrategyType processing) {
        return QueueDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic("t.main")
                .partitions(1)
                .submitStrategy(new SubmitStrategy(submit, 0))
                .processingStrategy(new ProcessingStrategy(processing, 0, 0, 0, 0))
                .build();
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }
}
