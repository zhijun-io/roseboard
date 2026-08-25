package com.roseboard.infrastructure.queue.config;

import java.util.UUID;

public record QueueTransportConfig(
        UUID id,
        UUID tenantId,
        String name,
        String topic,
        int pollInterval,
        int partitions,
        boolean consumerPerPartition,
        long packProcessingTimeout,
        SubmitStrategy submitStrategy,
        ProcessingStrategy processingStrategy) {
}
