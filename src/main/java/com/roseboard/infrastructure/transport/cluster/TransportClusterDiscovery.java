package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterNodeDiscovery;

import java.util.List;

/** TB {@code DiscoveryService} subset for transport nodes. */
public interface TransportClusterDiscovery extends ClusterNodeDiscovery {

    List<String> sortedTransportNodes();

    @Override
    default List<String> sortedNodes() {
        return sortedTransportNodes();
    }
}
