package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import org.springframework.context.ApplicationEvent;

import java.util.Set;

public final class PartitionChangeEvent extends ApplicationEvent {
    private final ClusterServiceType serviceType;
    private final Set<TopicPartitionInfo> partitions;
    private final PartitionChangeReason reason;

    public PartitionChangeEvent(Object source,
                                ClusterServiceType serviceType,
                                Set<TopicPartitionInfo> partitions,
                                PartitionChangeReason reason) {
        super(source);
        this.serviceType = serviceType;
        this.partitions = Set.copyOf(partitions);
        this.reason = reason;
    }

    public ClusterServiceType serviceType() {
        return serviceType;
    }

    public Set<TopicPartitionInfo> partitions() {
        return partitions;
    }

    public PartitionChangeReason reason() {
        return reason;
    }
}
