package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class ClusterSettings {
    private final boolean enabled;
    private final String nodeId;
    private final String discoveryType;
    private final String discoveryKey;
    private final long nodeTtlSeconds;
    private final long heartbeatIntervalMs;
    private final long recalculateDelayMs;
    private final List<Integer> assignedPartitions;
    private final ZookeeperSettings zookeeper;
    private transient String resolvedNodeId;

    public ClusterSettings(ClusterProperties cluster, TransportProperties transport) {
        TransportProperties.Cluster legacy = transport.getCluster();
        this.enabled = cluster.getEnabled() != null ? cluster.getEnabled() : legacy.isEnabled();
        this.nodeId = firstNonBlank(cluster.getNodeId(), legacy.getNodeId());
        this.discoveryType = firstNonBlank(cluster.getDiscoveryType(), legacy.getDiscoveryType());
        this.discoveryKey = firstNonBlank(cluster.getDiscoveryKey(), legacy.getDiscoveryKey());
        this.nodeTtlSeconds = cluster.getNodeTtlSeconds() != null
                ? cluster.getNodeTtlSeconds()
                : legacy.getNodeTtlSeconds();
        this.heartbeatIntervalMs = cluster.getHeartbeatIntervalMs() != null
                ? cluster.getHeartbeatIntervalMs()
                : legacy.getHeartbeatIntervalMs();
        this.recalculateDelayMs = cluster.getRecalculateDelayMs() != null
                ? cluster.getRecalculateDelayMs()
                : legacy.getRecalculateDelayMs();
        List<Integer> manual = !cluster.getAssignedPartitions().isEmpty()
                ? cluster.getAssignedPartitions()
                : legacy.getAssignedPartitions();
        this.assignedPartitions = manual == null ? List.of() : List.copyOf(manual);
        TransportProperties.Cluster.Zookeeper legacyZk = legacy.getZookeeper();
        ClusterProperties.Zookeeper clusterZk = cluster.getZookeeper();
        this.zookeeper = new ZookeeperSettings(
                firstNonBlank(clusterZk.getUrl(), legacyZk.getUrl()),
                firstNonBlank(clusterZk.getRootDir(), legacyZk.getRootDir()),
                firstNonBlank(clusterZk.getNodesDir(), "/nodes"),
                firstNonBlank(clusterZk.getTransportDir(), legacyZk.getTransportDir()),
                clusterZk.getSessionTimeoutMs() != null
                        ? clusterZk.getSessionTimeoutMs()
                        : legacyZk.getSessionTimeoutMs(),
                clusterZk.getConnectionTimeoutMs() != null
                        ? clusterZk.getConnectionTimeoutMs()
                        : legacyZk.getConnectionTimeoutMs(),
                clusterZk.getRetryIntervalMs() != null
                        ? clusterZk.getRetryIntervalMs()
                        : legacyZk.getRetryIntervalMs(),
                clusterZk.getMaxRetries() != null ? clusterZk.getMaxRetries() : legacyZk.getMaxRetries());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String resolvedNodeId() {
        if (resolvedNodeId == null) {
            resolvedNodeId = (nodeId == null || nodeId.isBlank())
                    ? "node-" + UUID.randomUUID()
                    : nodeId.trim();
        }
        return resolvedNodeId;
    }

    public String resolvedDiscoveryType() {
        if (!enabled) {
            return "memory";
        }
        if (discoveryType == null || discoveryType.isBlank()) {
            return "redis";
        }
        return discoveryType.trim().toLowerCase(Locale.ROOT);
    }

    public String getDiscoveryKey() {
        return discoveryKey;
    }

    public long getNodeTtlSeconds() {
        return nodeTtlSeconds;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public long getRecalculateDelayMs() {
        return recalculateDelayMs;
    }

    public List<Integer> getAssignedPartitions() {
        return assignedPartitions;
    }

    public ZookeeperSettings getZookeeper() {
        return zookeeper;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }

    public record ZookeeperSettings(
            String url,
            String rootDir,
            String nodesDir,
            String transportDir,
            int sessionTimeoutMs,
            int connectionTimeoutMs,
            int retryIntervalMs,
            int maxRetries) {

        public String resolvedNodesDir() {
            String root = rootDir == null || rootDir.isBlank() ? "/roseboard" : rootDir.trim();
            String nodes = nodesDir == null || nodesDir.isBlank() ? "/nodes" : nodesDir.trim();
            if (!root.startsWith("/")) {
                root = "/" + root;
            }
            if (!nodes.startsWith("/")) {
                nodes = "/" + nodes;
            }
            if (root.endsWith("/")) {
                root = root.substring(0, root.length() - 1);
            }
            return root + nodes;
        }

        public String resolvedTransportDir() {
            String root = rootDir == null || rootDir.isBlank() ? "/roseboard" : rootDir.trim();
            String transport = transportDir == null || transportDir.isBlank() ? "/transport" : transportDir.trim();
            if (!root.startsWith("/")) {
                root = "/" + root;
            }
            if (!transport.startsWith("/")) {
                transport = "/" + transport;
            }
            if (root.endsWith("/")) {
                root = root.substring(0, root.length() - 1);
            }
            return root + transport;
        }
    }
}
