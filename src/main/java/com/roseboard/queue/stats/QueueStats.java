package com.roseboard.queue.stats;

import java.util.UUID;

public record QueueStats(UUID id, long createdTime, UUID tenantId, String queueName, String serviceId) {
}
