package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.spi.QueueAdmin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks topics created for Memory queue bindings (including DLQ). */
public final class MemoryQueueAdmin implements QueueAdmin {
    private final Set<String> topics = ConcurrentHashMap.newKeySet();

    @Override
    public void createTopicIfNotExists(String topic, String properties, boolean force) {
        if (topic != null && !topic.isBlank()) {
            topics.add(topic);
        }
    }

    @Override
    public void destroy() {
        topics.clear();
    }

    @Override
    public void deleteTopic(String topic) {
        topics.remove(topic);
    }

    public boolean contains(String topic) {
        return topics.contains(topic);
    }
}
