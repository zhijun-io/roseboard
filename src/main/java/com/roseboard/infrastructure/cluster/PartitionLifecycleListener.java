package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.transport.cluster.ClusterServiceType;

import java.util.Set;

public interface PartitionLifecycleListener {

    default void onPartitionsAdded(ClusterServiceType serviceType, Set<TopicPartitionInfo> partitions) {
    }

    default void onPartitionsRemoved(ClusterServiceType serviceType, Set<TopicPartitionInfo> partitions) {
    }
}
