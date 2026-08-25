package com.roseboard.infrastructure.queue.config;

public record ProcessingStrategy(
        ProcessingStrategyType type,
        int retries,
        double failurePercentage,
        long pauseBetweenRetries,
        long maxPauseBetweenRetries
) {
}
