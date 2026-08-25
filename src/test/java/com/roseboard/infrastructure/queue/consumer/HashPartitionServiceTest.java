package com.roseboard.infrastructure.queue.consumer;

import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashPartitionServiceTest {

    @Test
    void resolveUsesStableHashForDeviceKey() {
        UUID deviceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        TopicPartitionInfo first = HashPartitionService.resolve("main", null, deviceId.toString(), 10, true);
        TopicPartitionInfo second = HashPartitionService.resolve("main", null, deviceId.toString(), 10, true);

        assertEquals(first.getPartition().orElseThrow(), second.getPartition().orElseThrow());
        assertTrue(first.getPartition().orElseThrow() >= 0);
        assertTrue(first.getPartition().orElseThrow() < 10);
        assertEquals("main", first.getTopic());
    }

    @Test
    void usesSha256HashingWithStableInputs() {
        UUID deviceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        assertEquals(-823647481, HashPartitionService.hash(deviceId));
        assertEquals(745595179, HashPartitionService.hash("key"));
        assertEquals(9, HashPartitionService.resolvePartitionIndex(deviceId, 10));
        assertEquals(3, HashPartitionService.resolvePartitionIndex("key", 4));
    }

    @Test
    void resolveDistributesKeysAcrossPartitions() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            TopicPartitionInfo partition = HashPartitionService.resolve(
                    "main", null, UUID.randomUUID().toString(), 4, false);
            seen.add(partition.getPartition().orElseThrow());
        }
        assertTrue(seen.size() > 1, "expected multiple partitions to be used");
    }

    @Test
    void hashTreatsNullAndEmptyAsZero() {
        assertEquals(0, HashPartitionService.hash((String) null));
        assertEquals(0, HashPartitionService.hash(""));
    }

    @Test
    void resolveRejectsInvalidPartitionCount() {
        assertThrows(IllegalArgumentException.class,
                () -> HashPartitionService.resolve("main", null, "key", 0, true));
    }

    @Test
    void assignedPartitionsReturnsAllWhenListEmpty() {
        Set<TopicPartitionInfo> partitions = HashPartitionService.assignedPartitions("main", 3, List.of());

        assertEquals(3, partitions.size());
        assertTrue(partitions.contains(new TopicPartitionInfo("main", null, 0, false, true)));
        assertTrue(partitions.contains(new TopicPartitionInfo("main", null, 1, false, true)));
        assertTrue(partitions.contains(new TopicPartitionInfo("main", null, 2, false, true)));
    }

    @Test
    void assignedPartitionsHonorsExplicitList() {
        Set<TopicPartitionInfo> partitions =
                HashPartitionService.assignedPartitions("notifications", 5, List.of(1, 3));

        assertEquals(2, partitions.size());
        assertTrue(partitions.contains(new TopicPartitionInfo("notifications", null, 1, false, true)));
        assertTrue(partitions.contains(new TopicPartitionInfo("notifications", null, 3, false, true)));
    }

    @Test
    void assignedPartitionsRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> HashPartitionService.assignedPartitions("main", 2, List.of(2)));
    }

    @Test
    void partitionIndicesOwnedByUsesModuloAssignment() {
        List<String> nodes = List.of("node-a", "node-b", "node-c");
        assertEquals(List.of(0, 3, 6, 9),
                HashPartitionService.partitionIndicesOwnedBy("node-a", 10, nodes));
        assertEquals(List.of(1, 4, 7),
                HashPartitionService.partitionIndicesOwnedBy("node-b", 10, nodes));
        assertEquals(List.of(2, 5, 8),
                HashPartitionService.partitionIndicesOwnedBy("node-c", 10, nodes));
    }

    @Test
    void partitionIndicesOwnedByReturnsEmptyForUnknownNode() {
        assertEquals(List.of(),
                HashPartitionService.partitionIndicesOwnedBy("missing", 10, List.of("node-a")));
    }

    @Test
    void murmur3HashIsStableForUuid() {
        UUID deviceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertEquals(HashPartitionService.hash(deviceId), HashPartitionService.hash(deviceId));
        assertEquals(
                HashPartitionService.resolvePartitionIndex(deviceId, 10),
                Math.floorMod(HashPartitionService.hash(deviceId), 10));
    }
}
