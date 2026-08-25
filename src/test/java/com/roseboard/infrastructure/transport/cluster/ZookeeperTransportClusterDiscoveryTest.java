package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterPartitionCoordinator;
import com.roseboard.infrastructure.cluster.ClusterProperties;
import com.roseboard.infrastructure.cluster.ClusterSettings;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ZookeeperTransportClusterDiscoveryTest {
    private TestingServer zkServer;

    @BeforeEach
    void setUp() throws Exception {
        zkServer = new TestingServer(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (zkServer != null) {
            zkServer.close();
        }
    }

    @Test
    void sortedNodesReturnsLexicographicOrder() {
        ClusterPartitionCoordinator coordinator = mock(ClusterPartitionCoordinator.class);
        ClusterSettings settingsA = settings("node-a");
        ClusterSettings settingsB = settings("node-b");

        ZookeeperTransportClusterDiscovery nodeA =
                new ZookeeperTransportClusterDiscovery(settingsA, coordinator);
        ZookeeperTransportClusterDiscovery nodeB =
                new ZookeeperTransportClusterDiscovery(settingsB, coordinator);

        nodeA.register();
        nodeB.register();

        assertEquals(List.of("node-a", "node-b"), nodeA.sortedNodes());

        nodeA.deregister();
        nodeB.deregister();
    }

    private ClusterSettings settings(String nodeId) {
        TransportProperties transport = new TransportProperties();
        transport.getCluster().setEnabled(true);
        transport.getCluster().setDiscoveryType("zookeeper");
        transport.getCluster().setNodeId(nodeId);
        transport.getCluster().getZookeeper().setUrl(zkServer.getConnectString());
        return new ClusterSettings(new ClusterProperties(), transport);
    }
}
