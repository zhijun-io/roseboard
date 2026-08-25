package com.roseboard.infrastructure.transport.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ConfigurationProperties(prefix = "roseboard.transport")
public class TransportProperties {
    private final Queue queue = new Queue();
    private final Cluster cluster = new Cluster();

    public Queue getQueue() {
        return queue;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public static class Queue {
        private int partitions = 10;
        private boolean consumerPerPartition = true;

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public boolean isConsumerPerPartition() {
            return consumerPerPartition;
        }

        public void setConsumerPerPartition(boolean consumerPerPartition) {
            this.consumerPerPartition = consumerPerPartition;
        }
    }

    public static class Cluster {
        private boolean enabled;
        private String nodeId;
        private String discoveryType = "memory";
        private String sessionStore = "memory";
        private String discoveryKey = "roseboard:cluster:transport:nodes";
        private String sessionKeyPrefix = "roseboard:transport:session:";
        private long nodeTtlSeconds = 15;
        private long sessionTtlSeconds = 900;
        private long heartbeatIntervalMs = 3000;
        private long recalculateDelayMs;
        private List<Integer> assignedPartitions = new ArrayList<>();
        private final Zookeeper zookeeper = new Zookeeper();
        private transient String resolvedNodeId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getDiscoveryType() {
            return discoveryType;
        }

        public void setDiscoveryType(String discoveryType) {
            this.discoveryType = discoveryType;
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

        public String getSessionStore() {
            return sessionStore;
        }

        public void setSessionStore(String sessionStore) {
            this.sessionStore = sessionStore;
        }

        public String getDiscoveryKey() {
            return discoveryKey;
        }

        public void setDiscoveryKey(String discoveryKey) {
            this.discoveryKey = discoveryKey;
        }

        public String getSessionKeyPrefix() {
            return sessionKeyPrefix;
        }

        public void setSessionKeyPrefix(String sessionKeyPrefix) {
            this.sessionKeyPrefix = sessionKeyPrefix;
        }

        public long getNodeTtlSeconds() {
            return nodeTtlSeconds;
        }

        public void setNodeTtlSeconds(long nodeTtlSeconds) {
            this.nodeTtlSeconds = nodeTtlSeconds;
        }

        public long getSessionTtlSeconds() {
            return sessionTtlSeconds;
        }

        public void setSessionTtlSeconds(long sessionTtlSeconds) {
            this.sessionTtlSeconds = sessionTtlSeconds;
        }

        public long getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public long getRecalculateDelayMs() {
            return recalculateDelayMs;
        }

        public void setRecalculateDelayMs(long recalculateDelayMs) {
            this.recalculateDelayMs = recalculateDelayMs;
        }

        public String resolvedNodeId() {
            if (resolvedNodeId == null) {
                resolvedNodeId = (nodeId == null || nodeId.isBlank())
                        ? "transport-" + UUID.randomUUID()
                        : nodeId.trim();
            }
            return resolvedNodeId;
        }

        public List<Integer> getAssignedPartitions() {
            return assignedPartitions;
        }

        public void setAssignedPartitions(List<Integer> assignedPartitions) {
            this.assignedPartitions = assignedPartitions == null ? new ArrayList<>() : assignedPartitions;
        }

        public Zookeeper getZookeeper() {
            return zookeeper;
        }

        public static class Zookeeper {
            private String url = "localhost:2181";
            private String rootDir = "/roseboard";
            private String transportDir = "/transport";
            private int sessionTimeoutMs = 3000;
            private int connectionTimeoutMs = 3000;
            private int retryIntervalMs = 3000;
            private int maxRetries = 3;

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getRootDir() {
                return rootDir;
            }

            public void setRootDir(String rootDir) {
                this.rootDir = rootDir;
            }

            public String getTransportDir() {
                return transportDir;
            }

            public void setTransportDir(String transportDir) {
                this.transportDir = transportDir;
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

            public int getSessionTimeoutMs() {
                return sessionTimeoutMs;
            }

            public void setSessionTimeoutMs(int sessionTimeoutMs) {
                this.sessionTimeoutMs = sessionTimeoutMs;
            }

            public int getConnectionTimeoutMs() {
                return connectionTimeoutMs;
            }

            public void setConnectionTimeoutMs(int connectionTimeoutMs) {
                this.connectionTimeoutMs = connectionTimeoutMs;
            }

            public int getRetryIntervalMs() {
                return retryIntervalMs;
            }

            public void setRetryIntervalMs(int retryIntervalMs) {
                this.retryIntervalMs = retryIntervalMs;
            }

            public int getMaxRetries() {
                return maxRetries;
            }

            public void setMaxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
            }
        }
    }
}
