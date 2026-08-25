package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.spi.QueueConsumerProvider;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;

/**
 * Memory provider seam. Consumer group comes from {@link com.roseboard.infrastructure.queue.QueueCoordinator#registerBinding}.
 */
public final class InMemoryQueueProvider implements QueueProducerProvider, QueueConsumerProvider {
    private final InMemoryQueueStorage storage;

    public InMemoryQueueProvider(InMemoryQueueStorage storage) {
        this.storage = storage;
    }

    public InMemoryQueueProvider() {
        this(new InMemoryQueueStorage());
    }

    @Override
    public QueueProducer<QueueMessage> createProducer(QueueTransportConfig config) {
        return new InMemoryQueueProducer(storage, config);
    }

    @Override
    public QueueConsumer<QueueMessage> createConsumer(QueueTransportConfig config, String consumerGroup) {
        return new InMemoryQueueConsumer(storage, config, consumerGroup);
    }

    public InMemoryQueueStorage storage() {
        return storage;
    }

    public QueueAdmin admin() {
        return NoOpQueueAdmin.INSTANCE;
    }
}
