package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.consumer.HashPartitionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCoreIsolationPartitionTest {

    @Test
    void isolatedTenantUsesIsolatedTopicName() {
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID deviceId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        TopicPartitionInfo shared = HashPartitionService.resolve(
                "tb_core.main", null, deviceId, 10, false);
        TopicPartitionInfo isolated = HashPartitionService.resolve(
                "tb_core.main", tenantId, deviceId, 10, false);

        assertTrue(shared.getFullTopicName().startsWith("tb_core.main."));
        assertTrue(isolated.getFullTopicName().contains(".isolated." + tenantId));
    }
}
