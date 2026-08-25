package com.roseboard.infrastructure.queue.processing;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmitStrategySequentialOriginatorTest {

    @Test
    void processesMessagesWithTheSameOriginatorInPackOrder() {
        QueueTransportConfig config = new QueueTransportConfig(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "main",
                "queue.main",
                100,
                1,
                false,
                1000,
                new SubmitStrategy(SubmitStrategyType.SEQUENTIAL_BY_ORIGINATOR, 0),
                new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0));
        List<String> handled = new ArrayList<>();
        CompletableFuture<Void> first = new CompletableFuture<>();

        CompletionStage<Void> result = QueueMsgPackPipeline.create(config, (id, message) -> {
            handled.add(new String(message.getData(), StandardCharsets.UTF_8));
            return handled.size() == 1 ? first : CompletableFuture.completedFuture(null);
        }).apply(List.of(message("device-1", "first"), message("device-1", "second")));

        assertEquals(List.of("first"), handled);

        first.complete(null);
        result.toCompletableFuture().join();

        assertEquals(List.of("first", "second"), handled);
    }

    private static QueueMessage message(String key, String data) {
        return new DefaultQueueMessage(key, data.getBytes(StandardCharsets.UTF_8), null);
    }
}
