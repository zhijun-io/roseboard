package com.roseboard.infrastructure.websocket.limit;

import com.roseboard.infrastructure.websocket.cluster.WebSocketClusterProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketLimitStore {
    private static final DefaultRedisScript<Long> TRY_ADD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
              return 1
            end
            if tonumber(ARGV[2]) > 0 and redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('SADD', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final Backend backend;

    public WebSocketLimitStore(WebSocketClusterProperties clusterProperties, StringRedisTemplate redis) {
        this.backend = clusterProperties.isEnabled() ? new RedisBackend(redis) : new InMemoryBackend();
    }

    public boolean tryAdd(String bucketKey, String member, long limit) {
        return backend.tryAdd(bucketKey, member, limit);
    }

    public void remove(String bucketKey, String member) {
        backend.remove(bucketKey, member);
    }

    public void removeByPrefix(String bucketKey, String memberPrefix) {
        backend.removeByPrefix(bucketKey, memberPrefix);
    }

    public static String tenantSessions(UUID tenantId) {
        return "tenant-session:" + tenantId;
    }

    public static String customerSessions(UUID customerId) {
        return "customer-session:" + customerId;
    }

    public static String regularUserSessions(UUID userId) {
        return "regular-user-session:" + userId;
    }

    public static String publicUserSessions(UUID tenantId) {
        return "public-user-session:" + tenantId;
    }

    public static String tenantSubscriptions(UUID tenantId) {
        return "tenant-sub:" + tenantId;
    }

    public static String customerSubscriptions(UUID customerId) {
        return "customer-sub:" + customerId;
    }

    public static String regularUserSubscriptions(UUID userId) {
        return "regular-user-sub:" + userId;
    }

    public static String publicUserSubscriptions(UUID tenantId) {
        return "public-user-sub:" + tenantId;
    }

    public static String subscriptionId(String sessionId, int cmdId) {
        return "[" + sessionId + "]:[" + cmdId + "]";
    }

    public static String sessionPrefix(String sessionId) {
        return "[" + sessionId + "]:";
    }

    private interface Backend {
        boolean tryAdd(String bucketKey, String member, long limit);

        void remove(String bucketKey, String member);

        void removeByPrefix(String bucketKey, String memberPrefix);
    }

    private static final class InMemoryBackend implements Backend {
        private final ConcurrentMap<String, Set<String>> buckets = new ConcurrentHashMap<>();

        @Override
        public boolean tryAdd(String bucketKey, String member, long limit) {
            if (limit <= 0) {
                return true;
            }
            Set<String> members = buckets.computeIfAbsent(bucketKey, ignored -> ConcurrentHashMap.newKeySet());
            synchronized (members) {
                if (members.contains(member)) {
                    return true;
                }
                if (members.size() >= limit) {
                    return false;
                }
                members.add(member);
                return true;
            }
        }

        @Override
        public void remove(String bucketKey, String member) {
            Set<String> members = buckets.get(bucketKey);
            if (members != null) {
                synchronized (members) {
                    members.remove(member);
                }
            }
        }

        @Override
        public void removeByPrefix(String bucketKey, String memberPrefix) {
            Set<String> members = buckets.get(bucketKey);
            if (members != null) {
                synchronized (members) {
                    members.removeIf(member -> member.startsWith(memberPrefix));
                }
            }
        }
    }

    private static final class RedisBackend implements Backend {
        private final StringRedisTemplate redis;

        RedisBackend(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public boolean tryAdd(String bucketKey, String member, long limit) {
            if (limit <= 0) {
                return true;
            }
            Long result = redis.execute(TRY_ADD_SCRIPT, List.of(redisKey(bucketKey)), member, Long.toString(limit));
            return result != null && result == 1L;
        }

        @Override
        public void remove(String bucketKey, String member) {
            redis.opsForSet().remove(redisKey(bucketKey), member);
        }

        @Override
        public void removeByPrefix(String bucketKey, String memberPrefix) {
            String key = redisKey(bucketKey);
            Set<String> members = redis.opsForSet().members(key);
            if (members == null || members.isEmpty()) {
                return;
            }
            Object[] toRemove = members.stream()
                    .filter(member -> member.startsWith(memberPrefix))
                    .toArray();
            if (toRemove.length > 0) {
                redis.opsForSet().remove(key, toRemove);
            }
        }

        private static String redisKey(String bucketKey) {
            return "roseboard:ws:limit:" + bucketKey;
        }
    }
}
