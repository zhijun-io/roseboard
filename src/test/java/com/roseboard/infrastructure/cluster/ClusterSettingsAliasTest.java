package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSettingsAliasTest {

    @Test
    void transportClusterConfigIsUsedWhenClusterPropertiesEmpty() {
        TransportProperties transport = new TransportProperties();
        transport.getCluster().setEnabled(true);
        transport.getCluster().setNodeId("node-from-transport");
        transport.getCluster().setDiscoveryType("zookeeper");
        transport.getCluster().setRecalculateDelayMs(500);

        ClusterSettings settings = new ClusterSettings(new ClusterProperties(), transport);

        assertTrue(settings.isEnabled());
        assertEquals("node-from-transport", settings.resolvedNodeId());
        assertEquals("zookeeper", settings.resolvedDiscoveryType());
        assertEquals(500, settings.getRecalculateDelayMs());
    }

    @Test
    void canonicalClusterConfigOverridesTransportAlias() {
        TransportProperties transport = new TransportProperties();
        transport.getCluster().setEnabled(false);
        transport.getCluster().setNodeId("legacy-node");
        transport.getCluster().setDiscoveryType("memory");

        ClusterProperties cluster = new ClusterProperties();
        cluster.setEnabled(true);
        cluster.setNodeId("canonical-node");
        cluster.setDiscoveryType("redis");

        ClusterSettings settings = new ClusterSettings(cluster, transport);

        assertTrue(settings.isEnabled());
        assertEquals("canonical-node", settings.resolvedNodeId());
        assertEquals("redis", settings.resolvedDiscoveryType());
    }
}
