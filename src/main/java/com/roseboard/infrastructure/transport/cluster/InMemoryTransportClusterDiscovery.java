package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnExpression("${roseboard.cluster.enabled:${roseboard.transport.cluster.enabled:false}} == false "
        + "|| '${roseboard.cluster.discovery-type:${roseboard.transport.cluster.discovery-type:memory}}' == 'memory'")
public class InMemoryTransportClusterDiscovery implements TransportClusterDiscovery {
    private final ClusterSettings settings;

    public InMemoryTransportClusterDiscovery(ClusterSettings settings) {
        this.settings = settings;
    }

    @Override
    public void register() {
    }

    @Override
    public void heartbeat() {
    }

    @Override
    public void deregister() {
    }

    @Override
    public List<String> sortedTransportNodes() {
        return List.of(settings.resolvedNodeId());
    }
}
