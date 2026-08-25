package com.roseboard.infrastructure.transport.cluster;

import com.roseboard.infrastructure.cluster.ClusterSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnExpression("${roseboard.cluster.enabled:${roseboard.transport.cluster.enabled:false}} == true "
        + "&& '${roseboard.cluster.discovery-type:${roseboard.transport.cluster.discovery-type:redis}}' == 'redis'")
public class RedisTransportClusterDiscovery implements TransportClusterDiscovery {
    private static final Logger log = LoggerFactory.getLogger(RedisTransportClusterDiscovery.class);

    private final StringRedisTemplate redis;
    private final ClusterSettings settings;
    private final String nodeId;

    public RedisTransportClusterDiscovery(StringRedisTemplate redis, ClusterSettings settings) {
        this.redis = redis;
        this.settings = settings;
        this.nodeId = settings.resolvedNodeId();
    }

    @Override
    public void register() {
        heartbeat();
        log.info("Registered transport node {} in cluster discovery", nodeId);
    }

    @Override
    public void heartbeat() {
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(discoveryKey(), nodeId, now);
        redis.opsForZSet().removeRangeByScore(discoveryKey(), 0, now - ttlMillis());
    }

    @Override
    @PreDestroy
    public void deregister() {
        redis.opsForZSet().remove(discoveryKey(), nodeId);
    }

    @Override
    public List<String> sortedTransportNodes() {
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(discoveryKey(), 0, now - ttlMillis());
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(discoveryKey(), 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of(nodeId);
        }
        List<String> nodes = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null) {
                nodes.add(tuple.getValue());
            }
        }
        nodes.sort(String::compareTo);
        return nodes;
    }

    private String discoveryKey() {
        return settings.getDiscoveryKey();
    }

    private long ttlMillis() {
        return Math.max(5_000L, settings.getNodeTtlSeconds() * 1_000L);
    }
}
