package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.spi.QueueAdmin;

public enum NoOpQueueAdmin implements QueueAdmin {
    INSTANCE;

    @Override
    public void createTopicIfNotExists(String topic, String properties, boolean force) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void deleteTopic(String topic) {
    }
}
