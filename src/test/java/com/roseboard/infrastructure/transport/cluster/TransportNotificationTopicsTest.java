package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportNotificationTopicsTest {

    @Test
    void buildsPerNodeTopicLikeTb() {
        TopicPartitionInfo partition = TransportNotificationTopics.partitionForNode(
                "tb_transport.notifications", "transport-1");

        assertEquals("tb_transport.notifications.transport-1", partition.getTopic());
        assertEquals("tb_transport.notifications.transport-1", partition.getFullTopicName());
    }

    @Test
    void rejectsBlankNodeId() {
        assertThrows(IllegalArgumentException.class,
                () -> TransportNotificationTopics.topicForNode("tb_transport.notifications", " "));
    }
}
