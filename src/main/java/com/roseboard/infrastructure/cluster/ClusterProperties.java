package com.roseboard.infrastructure.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "roseboard.cluster")
public class ClusterProperties {
    private Boolean enabled;
    private String nodeId;
    private String discoveryType;
    private String discoveryKey;
    private Long nodeTtlSeconds;
    private Long heartbeatIntervalMs;
    private Long recalculateDelayMs;
    private List<Integer> assignedPartitions = new ArrayList<>();
    private final Zookeeper zookeeper = new Zookeeper();

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
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

    public String getDiscoveryKey() {
        return discoveryKey;
    }

    public void setDiscoveryKey(String discoveryKey) {
        this.discoveryKey = discoveryKey;
    }

    public Long getNodeTtlSeconds() {
        return nodeTtlSeconds;
    }

    public void setNodeTtlSeconds(Long nodeTtlSeconds) {
        this.nodeTtlSeconds = nodeTtlSeconds;
    }

    public Long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(Long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public Long getRecalculateDelayMs() {
        return recalculateDelayMs;
    }

    public void setRecalculateDelayMs(Long recalculateDelayMs) {
        this.recalculateDelayMs = recalculateDelayMs;
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
        private String url;
        private String rootDir;
        private String nodesDir;
        private String transportDir;
        private Integer sessionTimeoutMs;
        private Integer connectionTimeoutMs;
        private Integer retryIntervalMs;
        private Integer maxRetries;

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

        public String getNodesDir() {
            return nodesDir;
        }

        public void setNodesDir(String nodesDir) {
            this.nodesDir = nodesDir;
        }

        public String getTransportDir() {
            return transportDir;
        }

        public void setTransportDir(String transportDir) {
            this.transportDir = transportDir;
        }

        public Integer getSessionTimeoutMs() {
            return sessionTimeoutMs;
        }

        public void setSessionTimeoutMs(Integer sessionTimeoutMs) {
            this.sessionTimeoutMs = sessionTimeoutMs;
        }

        public Integer getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(Integer connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }

        public Integer getRetryIntervalMs() {
            return retryIntervalMs;
        }

        public void setRetryIntervalMs(Integer retryIntervalMs) {
            this.retryIntervalMs = retryIntervalMs;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
