package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;

/** TB {@code TopicService.getNotificationsTopic(TB_TRANSPORT, serviceId)} equivalent. */
public final class TransportNotificationTopics {
    private TransportNotificationTopics() {
    }

    public static String topicForNode(String baseTopic, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        return baseTopic + "." + nodeId.trim();
    }

    public static TopicPartitionInfo partitionForNode(String baseTopic, String nodeId) {
        return new TopicPartitionInfo(topicForNode(baseTopic, nodeId), null, null, false, true);
    }
}
