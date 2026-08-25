package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.spi.QueueConsumerProvider;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.deadletter.DeadLetterPublisher;
import com.roseboard.infrastructure.queue.deadletter.DeadLetterTopics;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import com.roseboard.infrastructure.queue.processing.QueueMsgPackPipeline;
import com.roseboard.infrastructure.queue.spi.QueueMessageHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * 队列协调入口：binding、consumer 生命周期与分区热更新。不依赖领域持久化。
 */
public final class QueueCoordinator {

    private final QueueProperties properties;
    private final QueueConsumerProvider consumerProvider;
    private final QueueProducerProvider producerProvider;
    private final QueueAdmin admin;
    private final ExecutorService consumerExecutor;
    private final ExecutorService workerExecutor;
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, QueueConsumerManager<? extends QueueMessage>> managers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> queueLocks = new ConcurrentHashMap<>();

    public QueueCoordinator(QueueProperties properties,
                               QueueConsumerProvider consumerProvider,
                               QueueProducerProvider producerProvider,
                               QueueAdmin admin,
                               ExecutorService queueConsumerExecutor,
                               ExecutorService queueWorkerExecutor) {
        this.properties = properties;
        this.consumerProvider = consumerProvider;
        this.producerProvider = producerProvider;
        this.admin = admin;
        this.consumerExecutor = queueConsumerExecutor;
        this.workerExecutor = queueWorkerExecutor;
    }

    public String activeProvider() {
        return properties.getProvider() == null ? "memory" : properties.getProvider().trim().toLowerCase();
    }

    public void registerBinding(String queueName,
                              String consumerGroup,
                              QueueMessageHandler<QueueMessage> handler) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("queueName must be non-empty");
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must be non-empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is required");
        }
        if (bindings.putIfAbsent(queueName, new Binding(consumerGroup, handler)) != null) {
            throw new IllegalStateException("Duplicate binding for queue " + queueName);
        }
    }

    public Optional<QueueConsumerManager<? extends QueueMessage>> findManager(String queueName) {
        return Optional.ofNullable(managers.get(queueName));
    }

    public void updateHashPartitionedConsumers(
            Function<String, Set<TopicPartitionInfo>> partitionsForTopic) {
        for (String queueName : bindings.keySet()) {
            findManager(queueName).ifPresent(manager -> {
                if (!manager.config().consumerPerPartition()) {
                    return;
                }
                manager.update(partitionsForTopic.apply(manager.config().topic()));
            });
        }
    }

    public void startIfBound(QueueTransportConfig transport) {
        Binding binding = bindings.get(transport.name());
        if (binding == null) {
            return;
        }
        synchronized (lockFor(transport.name())) {
            if (managers.containsKey(transport.name())) {
                return;
            }
            startManager(transport.name(), transport, binding);
        }
    }

    public void restartIfBound(QueueTransportConfig transport) {
        Binding binding = bindings.get(transport.name());
        if (binding == null) {
            return;
        }
        QueueMsgPackPipeline.requireSupportedConfig(transport);
        synchronized (lockFor(transport.name())) {
            QueueTransportConfig previous = findManager(transport.name())
                    .map(QueueConsumerManager::config)
                    .orElse(null);
            stopManager(transport.name());
            try {
                startManager(transport.name(), transport, binding);
            } catch (RuntimeException ex) {
                if (previous != null) {
                    startManager(transport.name(), previous, binding);
                }
                throw ex;
            }
        }
    }

    public void stopIfBound(String queueName) {
        if (!bindings.containsKey(queueName)) {
            return;
        }
        synchronized (lockFor(queueName)) {
            stopManager(queueName);
        }
    }

    private void stopManager(String queueName) {
        QueueConsumerManager<? extends QueueMessage> manager = managers.remove(queueName);
        if (manager != null) {
            manager.stop();
            manager.awaitStop();
        }
    }

    private void startManager(String queueName, QueueTransportConfig transport, Binding binding) {
        if (managers.containsKey(queueName)) {
            throw new IllegalStateException("Queue consumer manager already running for " + queueName);
        }
        for (int i = 0; i < transport.partitions(); i++) {
            admin.createTopicIfNotExists(
                    new TopicPartitionInfo(transport.topic(), null, i, false, true).getFullTopicName());
        }
        DeadLetterPublisher deadLetterPublisher = DeadLetterPublisher.create(producerProvider, admin, transport);
        admin.createTopicIfNotExists(DeadLetterTopics.resolve(transport.topic()));
        Function<List<QueueMessage>, CompletionStage<Void>> processor =
                QueueMsgPackPipeline.create(transport, binding.handler(), deadLetterPublisher);
        QueueConsumerManager<QueueMessage> manager = QueueConsumerManager.<QueueMessage>builder()
                .queueKey(queueName)
                .config(transport)
                .consumerCreator((cfg, tpi) -> consumerProvider.createConsumer(cfg, binding.consumerGroup()))
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(processor::apply)
                .build();
        assignPartitions(manager, transport);
        managers.put(queueName, manager);
    }

    private static void assignPartitions(QueueConsumerManager<QueueMessage> manager, QueueTransportConfig transport) {
        if (!transport.consumerPerPartition()) {
            manager.update(Set.of(new TopicPartitionInfo(transport.topic(), null, null, true)));
            return;
        }
        HashSet<TopicPartitionInfo> partitions = new HashSet<>();
        for (int i = 0; i < transport.partitions(); i++) {
            partitions.add(new TopicPartitionInfo(transport.topic(), null, i, false, true));
        }
        manager.update(partitions);
    }

    private Object lockFor(String queueName) {
        return queueLocks.computeIfAbsent(queueName, ignored -> new Object());
    }

    private record Binding(String consumerGroup, QueueMessageHandler<QueueMessage> handler) {
    }
}
