package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.transport.cluster.ClusterServiceType;
import com.roseboard.infrastructure.transport.cluster.PartitionChangeEvent;
import com.roseboard.infrastructure.transport.cluster.PartitionChangeReason;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.infrastructure.queue.consumer.HashPartitionService;
import com.roseboard.tenant.profile.TenantCoreIsolationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class CorePartitionManager {
    private static final Logger log = LoggerFactory.getLogger(CorePartitionManager.class);

    private final ClusterSettings settings;
    private final TransportProperties transportProperties;
    private final ClusterNodeDiscovery discovery;
    private final TenantCoreIsolationService tenantIsolation;
    private final ApplicationEventPublisher events;
    private final List<PartitionLifecycleListener> lifecycleListeners;
    private volatile Set<TopicPartitionInfo> currentPartitions = Set.of();

    public CorePartitionManager(ClusterSettings settings,
                                TransportProperties transportProperties,
                                ClusterNodeDiscovery discovery,
                                TenantCoreIsolationService tenantIsolation,
                                ApplicationEventPublisher events,
                                List<PartitionLifecycleListener> lifecycleListeners) {
        this.settings = settings;
        this.transportProperties = transportProperties;
        this.discovery = discovery;
        this.tenantIsolation = tenantIsolation;
        this.events = events;
        this.lifecycleListeners = lifecycleListeners == null ? List.of() : lifecycleListeners;
    }

    public Set<TopicPartitionInfo> currentPartitions() {
        return currentPartitions;
    }

    public void refresh(String mainTopic) {
        if (!settings.isEnabled()) {
            return;
        }
        Set<TopicPartitionInfo> next = computePartitions(mainTopic);
        Set<TopicPartitionInfo> previous = currentPartitions;
        currentPartitions = next;
        if (previous.equals(next)) {
            return;
        }
        PartitionChangeReason reason = previous.isEmpty()
                ? PartitionChangeReason.INITIAL
                : PartitionChangeReason.TOPOLOGY;
        log.info("Core queue partitions for node {} changed: {} -> {}",
                settings.resolvedNodeId(),
                partitionNumbers(previous),
                partitionNumbers(next));
        Set<TopicPartitionInfo> added = new HashSet<>(next);
        added.removeAll(previous);
        Set<TopicPartitionInfo> removed = new HashSet<>(previous);
        removed.removeAll(next);
        if (!removed.isEmpty()) {
            lifecycleListeners.forEach(listener ->
                    listener.onPartitionsRemoved(ClusterServiceType.TB_CORE, removed));
        }
        if (!added.isEmpty()) {
            lifecycleListeners.forEach(listener ->
                    listener.onPartitionsAdded(ClusterServiceType.TB_CORE, added));
        }
        events.publishEvent(new PartitionChangeEvent(
                this, ClusterServiceType.TB_CORE, next, reason));
    }

    public Set<TopicPartitionInfo> computePartitions(String topic) {
        int partitionCount = transportProperties.getQueue().getPartitions();
        // Match DefaultTransportService.partitionFor(): per-partition consumers
        // use physical topic suffixes, while a shared consumer uses the
        // provider's internal partition key.
        boolean useInternal = !transportProperties.getQueue().isConsumerPerPartition();
        List<Integer> manual = settings.getAssignedPartitions();
        if (!manual.isEmpty()) {
            return withIsolatedTenants(
                    topic,
                    HashPartitionService.assignedPartitions(topic, null, partitionCount, manual, useInternal),
                    manual,
                    partitionCount,
                    useInternal);
        }
        List<String> nodes = discovery.sortedNodes();
        if (nodes.isEmpty()) {
            return HashPartitionService.assignedPartitions(topic, null, partitionCount, List.of(), useInternal);
        }
        List<Integer> owned = HashPartitionService.partitionIndicesOwnedBy(
                settings.resolvedNodeId(),
                partitionCount,
                nodes);
        Set<TopicPartitionInfo> shared = owned.isEmpty()
                ? Set.of()
                : HashPartitionService.assignedPartitions(
                        topic, null, partitionCount, owned, useInternal);
        return withIsolatedTenants(topic, shared, owned, partitionCount, useInternal);
    }

    private Set<TopicPartitionInfo> withIsolatedTenants(String topic,
                                                        Set<TopicPartitionInfo> shared,
                                                        List<Integer> ownedIndices,
                                                        int partitionCount,
                                                        boolean useInternal) {
        List<UUID> isolatedTenants = tenantIsolation.isolatedTenantIds();
        if (isolatedTenants.isEmpty()) {
            return shared;
        }
        Set<TopicPartitionInfo> all = new HashSet<>(shared);
        for (UUID tenantId : isolatedTenants) {
            all.addAll(HashPartitionService.assignedPartitions(
                    topic, tenantId, partitionCount, ownedIndices, useInternal));
        }
        return all;
    }

    private static List<Integer> partitionNumbers(Set<TopicPartitionInfo> partitions) {
        return partitions.stream()
                .map(tpi -> tpi.getPartition().orElse(-1))
                .sorted()
                .toList();
    }
}
