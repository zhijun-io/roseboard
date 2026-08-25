package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.QueueMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-local Memory storage. Messages are keyed by Queue ID and full topic name.
 * Each consumer holds at most one leased in-flight batch.
 */
public final class InMemoryQueueStorage {
    private final ConcurrentHashMap<String, TopicQueue> topics = new ConcurrentHashMap<>();

    public void put(UUID queueId, String fullTopicName, QueueMessage message) {
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(fullTopicName, "fullTopicName");
        Objects.requireNonNull(message, "message");
        topic(queueId, fullTopicName).offer(copy(message));
    }

    public List<QueueMessage> lease(UUID queueId, String consumerGroup, String fullTopicName, int maxBatch) {
        return topic(queueId, fullTopicName).lease(consumerGroup, maxBatch);
    }

    public void commit(UUID queueId, String consumerGroup, String fullTopicName) {
        topic(queueId, fullTopicName).commit(consumerGroup);
    }

    public void restore(UUID queueId, String consumerGroup, String fullTopicName) {
        topic(queueId, fullTopicName).restore(consumerGroup);
    }

    public boolean hasInFlight(UUID queueId, String consumerGroup, String fullTopicName) {
        return topic(queueId, fullTopicName).hasInFlight(consumerGroup);
    }

    public boolean isProcessLocalOnly() {
        return true;
    }

    private TopicQueue topic(UUID queueId, String fullTopicName) {
        return topics.computeIfAbsent(queueId + "\0" + fullTopicName, ignored -> new TopicQueue());
    }

    private static QueueMessage copy(QueueMessage message) {
        return new DefaultQueueMessage(message);
    }

    private static final class TopicQueue {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<QueueMessage> pending = new ArrayDeque<>();
        private final ConcurrentHashMap<String, List<QueueMessage>> inFlight = new ConcurrentHashMap<>();

        void offer(QueueMessage message) {
            lock.lock();
            try {
                pending.addLast(message);
            } finally {
                lock.unlock();
            }
        }

        List<QueueMessage> lease(String consumerGroup, int maxBatch) {
            lock.lock();
            try {
                if (inFlight.containsKey(consumerGroup)) {
                    return List.of();
                }
                if (pending.isEmpty()) {
                    return List.of();
                }
                List<QueueMessage> batch = new ArrayList<>();
                int limit = Math.max(1, maxBatch);
                while (!pending.isEmpty() && batch.size() < limit) {
                    batch.add(pending.removeFirst());
                }
                inFlight.put(consumerGroup, List.copyOf(batch));
                return List.copyOf(batch);
            } finally {
                lock.unlock();
            }
        }

        void commit(String consumerGroup) {
            lock.lock();
            try {
                inFlight.remove(consumerGroup);
            } finally {
                lock.unlock();
            }
        }

        void restore(String consumerGroup) {
            lock.lock();
            try {
                List<QueueMessage> batch = inFlight.remove(consumerGroup);
                if (batch == null || batch.isEmpty()) {
                    return;
                }
                for (int i = batch.size() - 1; i >= 0; i--) {
                    pending.addFirst(batch.get(i));
                }
            } finally {
                lock.unlock();
            }
        }

        boolean hasInFlight(String consumerGroup) {
            return inFlight.containsKey(consumerGroup);
        }
    }
}
