package com.roseboard.infrastructure.queue.spi;

import com.roseboard.infrastructure.queue.QueueMessage;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Handles one queue message. The returned stage must complete when the message
 * side effects are finished; the runtime uses that completion for retry and
 * commit decisions.
 */
@FunctionalInterface
public interface QueueMessageHandler<M extends QueueMessage> {

    CompletionStage<Void> handle(UUID messageId, M message);
}
