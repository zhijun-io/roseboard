package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.transport.cluster.ClusterServiceType;
import com.roseboard.infrastructure.transport.cluster.PartitionChangeEvent;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorePartitionManagerTest {
    private static final String TOPIC = "tb_core.main";

    private TransportProperties transportProperties;
    private ClusterSettings settings;
    private StubDiscovery discovery;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private com.roseboard.tenant.profile.TenantCoreIsolationService tenantIsolation;

    private CorePartitionManager manager;

    @BeforeEach
    void setUp() {
        transportProperties = new TransportProperties();
        transportProperties.getCluster().setEnabled(true);
        transportProperties.getCluster().setNodeId("node-b");
        ClusterProperties clusterProperties = new ClusterProperties();
        settings = new ClusterSettings(clusterProperties, transportProperties);
        discovery = new StubDiscovery(List.of("node-a", "node-b", "node-c"));
        manager = new CorePartitionManager(
                settings, transportProperties, discovery, tenantIsolation, events, List.of());
    }

    @Test
    void computePartitionsAssignsByClusterTopology() {
        when(tenantIsolation.isolatedTenantIds()).thenReturn(List.of());
        Set<Integer> partitions = partitionNumbers(manager.computePartitions(TOPIC));

        assertEquals(Set.of(1, 4, 7), partitions);
    }

    @Test
    void computePartitionsHonorsManualOverride() {
        when(tenantIsolation.isolatedTenantIds()).thenReturn(List.of());
        transportProperties.getCluster().setAssignedPartitions(List.of(2, 8));
        settings = new ClusterSettings(new ClusterProperties(), transportProperties);
        manager = new CorePartitionManager(
                settings, transportProperties, discovery, tenantIsolation, events, List.of());

        Set<Integer> partitions = partitionNumbers(manager.computePartitions(TOPIC));

        assertEquals(Set.of(2, 8), partitions);
    }

    @Test
    void computePartitionsIncludesIsolatedTenants() {
        UUID tenantId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(tenantIsolation.isolatedTenantIds()).thenReturn(List.of(tenantId));

        Set<TopicPartitionInfo> partitions = manager.computePartitions(TOPIC);

        assertEquals(6, partitions.size());
        long isolated = partitions.stream()
                .filter(tpi -> tpi.getTenantId().filter(tenantId::equals).isPresent())
                .count();
        assertEquals(3, isolated);
        assertEquals(Set.of(1, 4, 7), partitions.stream()
                .filter(tpi -> tpi.getTenantId().isEmpty())
                .map(tpi -> tpi.getPartition().orElseThrow())
                .collect(Collectors.toSet()));
    }

    @Test
    void refreshPublishesTbCorePartitionChangeEventWhenPartitionsChange() {
        when(tenantIsolation.isolatedTenantIds()).thenReturn(List.of());
        manager.refresh(TOPIC);
        ArgumentCaptor<PartitionChangeEvent> captor = ArgumentCaptor.forClass(PartitionChangeEvent.class);
        verify(events).publishEvent(captor.capture());
        assertEquals(ClusterServiceType.TB_CORE, captor.getValue().serviceType());
        assertEquals(Set.of(1, 4, 7), partitionNumbers(captor.getValue().partitions()));

        manager.refresh(TOPIC);
        verify(events, times(1)).publishEvent(org.mockito.ArgumentMatchers.any());

        discovery.setNodes(List.of("node-a", "node-b"));
        manager.refresh(TOPIC);
        verify(events, times(2)).publishEvent(captor.capture());
        assertEquals(Set.of(1, 3, 5, 7, 9), partitionNumbers(captor.getValue().partitions()));
    }

    @Test
    void refreshDoesNotPublishWhenClusterDisabled() {
        transportProperties.getCluster().setEnabled(false);
        settings = new ClusterSettings(new ClusterProperties(), transportProperties);
        manager = new CorePartitionManager(
                settings, transportProperties, discovery, tenantIsolation, events, List.of());

        manager.refresh(TOPIC);

        verify(events, times(0)).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    private static Set<Integer> partitionNumbers(Set<TopicPartitionInfo> partitions) {
        return partitions.stream()
                .filter(tpi -> tpi.getTenantId().isEmpty())
                .map(tpi -> tpi.getPartition().orElseThrow())
                .collect(Collectors.toSet());
    }

    private static final class StubDiscovery implements ClusterNodeDiscovery {
        private List<String> nodes;

        StubDiscovery(List<String> nodes) {
            this.nodes = nodes;
        }

        void setNodes(List<String> nodes) {
            this.nodes = nodes;
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
        public List<String> sortedNodes() {
            return nodes;
        }
    }
}
