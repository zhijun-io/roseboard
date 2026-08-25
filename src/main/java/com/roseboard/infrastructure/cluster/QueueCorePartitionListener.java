package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.queue.QueueCoordinator;
import com.roseboard.infrastructure.transport.cluster.ClusterServiceType;
import com.roseboard.infrastructure.transport.cluster.PartitionChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class QueueCorePartitionListener {
    private final QueueCoordinator coordinator;
    private final CorePartitionManager corePartitionManager;

    QueueCorePartitionListener(QueueCoordinator coordinator,
                               CorePartitionManager corePartitionManager) {
        this.coordinator = coordinator;
        this.corePartitionManager = corePartitionManager;
    }

    @EventListener
    void onPartitionChange(PartitionChangeEvent event) {
        if (event.serviceType() != ClusterServiceType.TB_CORE) {
            return;
        }
        coordinator.updateHashPartitionedConsumers(corePartitionManager::computePartitions);
    }
}
