package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterPartitionCoordinator;
import com.roseboard.infrastructure.cluster.ClusterSettings;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnExpression("${roseboard.cluster.enabled:${roseboard.transport.cluster.enabled:false}} == true "
        + "&& '${roseboard.cluster.discovery-type:${roseboard.transport.cluster.discovery-type:memory}}' == 'zookeeper'")
public class ZookeeperTransportClusterDiscovery implements TransportClusterDiscovery {
    private static final Logger log = LoggerFactory.getLogger(ZookeeperTransportClusterDiscovery.class);

    private final ClusterSettings settings;
    private final ClusterPartitionCoordinator partitionCoordinator;
    private final String nodeId;
    private final CuratorFramework client;
    private final CuratorCache cache;
    private final String nodesDir;
    private final String nodePath;
    private volatile boolean started;

    public ZookeeperTransportClusterDiscovery(ClusterSettings settings,
                                              @Lazy ClusterPartitionCoordinator partitionCoordinator) {
        this.settings = settings;
        this.partitionCoordinator = partitionCoordinator;
        this.nodeId = settings.resolvedNodeId();
        ClusterSettings.ZookeeperSettings zk = settings.getZookeeper();
        this.nodesDir = zk.resolvedNodesDir();
        this.nodePath = nodesDir + "/" + nodeId;
        this.client = CuratorFrameworkFactory.builder()
                .connectString(zk.url())
                .sessionTimeoutMs(zk.sessionTimeoutMs())
                .connectionTimeoutMs(zk.connectionTimeoutMs())
                .retryPolicy(new ExponentialBackoffRetry(zk.retryIntervalMs(), zk.maxRetries()))
                .build();
        this.cache = CuratorCache.build(client, nodesDir);
    }

    @PostConstruct
    void postConstructStart() {
        ensureStarted();
        heartbeat();
        log.info("Registered cluster node {} in ZooKeeper under {}", nodeId, nodesDir);
    }

    @Override
    public void register() {
        ensureStarted();
        heartbeat();
    }

    @Override
    public void heartbeat() {
        ensureStarted();
        try {
            if (client.checkExists().forPath(nodePath) == null) {
                client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(nodePath, nodeId.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to register cluster node in ZooKeeper", exception);
        }
    }

    @Override
    @PreDestroy
    public void deregister() {
        if (!started) {
            return;
        }
        try {
            if (client.checkExists().forPath(nodePath) != null) {
                client.delete().forPath(nodePath);
            }
        } catch (Exception exception) {
            log.warn("Failed to deregister cluster node {} from ZooKeeper", nodeId, exception);
        } finally {
            closeQuietly(cache);
            closeQuietly(client);
            started = false;
        }
    }

    @Override
    public List<String> sortedTransportNodes() {
        ensureStarted();
        try {
            List<String> children = client.getChildren().forPath(nodesDir);
            if (children == null || children.isEmpty()) {
                return List.of(nodeId);
            }
            List<String> nodes = new ArrayList<>(children);
            nodes.sort(String::compareTo);
            return nodes;
        } catch (Exception exception) {
            log.warn("Failed to list cluster nodes from ZooKeeper, falling back to local node", exception);
            return List.of(nodeId);
        }
    }

    private synchronized void ensureStarted() {
        if (started) {
            return;
        }
        client.getConnectionStateListenable().addListener(new ConnectionStateListener() {
            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
                if (newState == ConnectionState.RECONNECTED) {
                    partitionCoordinator.scheduleRefresh();
                }
            }
        });
        cache.listenable().addListener(CuratorCacheListener.builder()
                .forCreates(node -> partitionCoordinator.scheduleRefresh())
                .forDeletes(oldNode -> partitionCoordinator.scheduleRefresh())
                .build());
        client.start();
        cache.start();
        started = true;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
