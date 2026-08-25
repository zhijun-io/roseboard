package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterPartitionCoordinator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(TransportProperties.class)
public class TransportClusterConfiguration {
    private final TransportClusterDiscovery discovery;
    private final ClusterPartitionCoordinator partitionCoordinator;

    public TransportClusterConfiguration(TransportClusterDiscovery discovery,
                                         ClusterPartitionCoordinator partitionCoordinator) {
        this.discovery = discovery;
        this.partitionCoordinator = partitionCoordinator;
    }

    @PostConstruct
    void registerNode() {
        discovery.register();
        partitionCoordinator.scheduleRefresh();
    }

    @PreDestroy
    void leaveCluster() {
        discovery.deregister();
    }

    @Scheduled(fixedDelayString =
            "${roseboard.cluster.heartbeat-interval-ms:${roseboard.transport.cluster.heartbeat-interval-ms:3000}}")
    void heartbeatAndRefreshPartitions() {
        partitionCoordinator.scheduleRefresh();
    }
}
