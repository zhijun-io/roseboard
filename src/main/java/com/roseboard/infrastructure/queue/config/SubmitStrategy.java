package com.roseboard.infrastructure.queue.config;

public record SubmitStrategy(SubmitStrategyType type, int batchSize) {
}
