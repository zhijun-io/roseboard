package com.roseboard.infrastructure.queue.consumer;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stable SHA-256 partition routing and partition ownership
 * ({@code partition % servers.size()}).
 *
 * <p>The first four bytes of the SHA-256 digest are used as the signed hash
 * value. Changing this algorithm changes the partition assigned to existing
 * keys.</p>
 */
public final class HashPartitionService {
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    });

    private HashPartitionService() {
    }

    public static TopicPartitionInfo resolve(String topic,
                                             UUID tenantId,
                                             UUID entityId,
                                             int partitionCount,
                                             boolean useInternalPartition) {
        return resolve(topic, tenantId, entityId.toString(), partitionCount, useInternalPartition, entityId);
    }

    public static TopicPartitionInfo resolve(String topic,
                                             UUID tenantId,
                                             String key,
                                             int partitionCount,
                                             boolean useInternalPartition) {
        UUID entityId = parseUuid(key);
        return resolve(topic, tenantId, key, partitionCount, useInternalPartition, entityId);
    }

    private static TopicPartitionInfo resolve(String topic,
                                              UUID tenantId,
                                              String key,
                                              int partitionCount,
                                              boolean useInternalPartition,
                                              UUID entityId) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be >= 1");
        }
        int partition = entityId != null
                ? resolvePartitionIndex(entityId, partitionCount)
                : resolvePartitionIndex(key, partitionCount);
        return new TopicPartitionInfo(topic, tenantId, partition, useInternalPartition, true);
    }

    public static int resolvePartitionIndex(UUID entityId, int partitionCount) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be >= 1");
        }
        return Math.floorMod(hash(entityId), partitionCount);
    }

    public static int resolvePartitionIndex(String key, int partitionCount) {
        UUID entityId = parseUuid(key);
        if (entityId != null) {
            return resolvePartitionIndex(entityId, partitionCount);
        }
        return Math.floorMod(hash(key), partitionCount);
    }

    /** TB {@code resolveByPartitionIdx} for non-RE queues: {@code servers.get(partition % servers.size())}. */
    public static List<Integer> partitionIndicesOwnedBy(String nodeId,
                                                        int partitionCount,
                                                        List<String> sortedNodeIds) {
        if (partitionCount < 1 || nodeId == null || nodeId.isBlank() || sortedNodeIds.isEmpty()) {
            return List.of();
        }
        int nodeIndex = sortedNodeIds.indexOf(nodeId);
        if (nodeIndex < 0) {
            return List.of();
        }
        int serverCount = sortedNodeIds.size();
        List<Integer> owned = new ArrayList<>();
        for (int partition = 0; partition < partitionCount; partition++) {
            if (partition % serverCount == nodeIndex) {
                owned.add(partition);
            }
        }
        return owned;
    }

    public static Set<TopicPartitionInfo> assignedPartitions(String topic, int partitionCount, List<Integer> assigned) {
        return assignedPartitions(topic, null, partitionCount, assigned, false);
    }

    public static Set<TopicPartitionInfo> assignedPartitions(String topic,
                                                             UUID tenantId,
                                                             int partitionCount,
                                                             List<Integer> assigned,
                                                             boolean useInternalPartition) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be >= 1");
        }
        HashSet<TopicPartitionInfo> partitions = new HashSet<>();
        if (assigned == null || assigned.isEmpty()) {
            for (int i = 0; i < partitionCount; i++) {
                partitions.add(new TopicPartitionInfo(topic, tenantId, i, useInternalPartition, true));
            }
            return partitions;
        }
        for (Integer partition : assigned) {
            if (partition == null || partition < 0 || partition >= partitionCount) {
                throw new IllegalArgumentException("Invalid assigned partition " + partition);
            }
            partitions.add(new TopicPartitionInfo(topic, tenantId, partition, useInternalPartition, true));
        }
        return partitions;
    }

    public static int hash(UUID key) {
        if (key == null) {
            return 0;
        }
        ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(key.getMostSignificantBits())
                .putLong(key.getLeastSignificantBits());
        return hashBytes(bytes.array());
    }

    public static int hash(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        return hashBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static int hashBytes(byte[] value) {
        byte[] digest = SHA_256.get().digest(value);
        return ((digest[0] & 0xff) << 24)
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
    }

    private static UUID parseUuid(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
