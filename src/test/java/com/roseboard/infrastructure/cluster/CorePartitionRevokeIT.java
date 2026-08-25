package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProducer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.consumer.QueueConsumerManager;
import com.roseboard.infrastructure.queue.processing.QueueMsgPackPipeline;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.tenant.profile.TenantCoreIsolationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-5: partition revoke waits for in-flight pack, commits, and prevents peer redelivery.
 */
class CorePartitionRevokeIT {

    private static final String CONSUMER_GROUP = "cluster-main";

    private ExecutorService consumerExecutor;
    private ExecutorService workerExecutor;
    private QueueConsumerManager<QueueMessage> nodeA;
    private QueueConsumerManager<QueueMessage> nodeB;

    @AfterEach
    void tearDown() {
        shutdown(nodeA, nodeB);
        shutdownExecutors();
    }

    @Test
    void revokeWaitsForInFlightThenCommitPreventsPeerRedelivery() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = definition(queueId, "revoke.main");
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo partition0 = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        TopicPartitionInfo partition1 = new TopicPartitionInfo(definition.topic(), null, 1, false, true);

        new InMemoryQueueProducer(storage, definition.toTransportConfig())
                .send(partition0, msg("device-1"), QueueCallback.EMPTY);

        CountDownLatch processingStarted = new CountDownLatch(1);
        CompletableFuture<Void> processingGate = new CompletableFuture<>();
        AtomicInteger processCount = new AtomicInteger();

        initExecutors();
        nodeA = manager(storage, definition, msgs -> {
            processCount.incrementAndGet();
            processingStarted.countDown();
            return processingGate;
        });
        nodeB = manager(storage, definition, msgs -> CompletableFuture.completedFuture(null));

        nodeA.update(Set.of(partition0));
        nodeB.update(Set.of(partition1));

        assertTrue(processingStarted.await(5, TimeUnit.SECONDS));

        Thread revokeThread = new Thread(() -> nodeA.update(Set.of()), "revoke-partition-0");
        revokeThread.start();
        assertTrue(processingStarted.await(0, TimeUnit.SECONDS));

        Thread.sleep(100);
        assertTrue(revokeThread.isAlive(), "revoke should block until in-flight pack completes");

        processingGate.complete(null);
        revokeThread.join(TimeUnit.SECONDS.toMillis(30));

        nodeB.update(Set.of(partition0, partition1));

        InMemoryQueueConsumer peer = new InMemoryQueueConsumer(
                storage, definition.toTransportConfig(), CONSUMER_GROUP);
        peer.subscribe(Set.of(partition0));
        List<String> redelivered = peer.poll(50).stream().map(QueueMessage::getKey).toList();

        assertEquals(List.of(), redelivered);
        assertEquals(1, processCount.get());
    }

    @Test
    void revokeWaitsForRetryBeforePeerCanReceiveMessage() throws Exception {
        UUID queueId = UUID.randomUUID();
        QueueDefinition definition = retryDefinition(queueId, "revoke.retry");
        InMemoryQueueStorage storage = new InMemoryQueueStorage();
        TopicPartitionInfo partition0 = new TopicPartitionInfo(definition.topic(), null, 0, false, true);
        InMemoryQueueProducer producer = new InMemoryQueueProducer(storage, definition.toTransportConfig());
        producer.send(partition0, msg("device-1"), QueueCallback.EMPTY);

        CountDownLatch retryStarted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        initExecutors();
        nodeA = manager(storage, definition, QueueMsgPackPipeline.create(
                definition.toTransportConfig(), (id, message) -> {
                    if (attempts.incrementAndGet() == 1) {
                        retryStarted.countDown();
                        return CompletableFuture.failedFuture(new IllegalStateException("retry"));
                    }
                    return CompletableFuture.completedFuture(null);
                })::apply);
        nodeB = manager(storage, definition, msgs -> CompletableFuture.completedFuture(null));
        nodeA.update(Set.of(partition0));
        assertTrue(retryStarted.await(5, TimeUnit.SECONDS));

        Thread revokeThread = new Thread(() -> nodeA.update(Set.of()), "revoke-retry-partition");
        revokeThread.start();
        revokeThread.join(TimeUnit.SECONDS.toMillis(30));
        assertEquals(false, revokeThread.isAlive());
        assertEquals(2, attempts.get());

        nodeB.update(Set.of(partition0));
        InMemoryQueueConsumer peer = new InMemoryQueueConsumer(
                storage, definition.toTransportConfig(), CONSUMER_GROUP);
        peer.subscribe(Set.of(partition0));
        assertEquals(List.of(), peer.poll(50).stream().map(QueueMessage::getKey).toList());
    }

    @Test
    void topologyShrinkPublishesRemovedPartitionsToLifecycleListener() {
        TransportProperties transport = new TransportProperties();
        transport.getCluster().setEnabled(true);
        transport.getCluster().setNodeId("node-a");
        ClusterSettings settings = new ClusterSettings(new ClusterProperties(), transport);

        StubDiscovery discovery = new StubDiscovery(List.of("node-a", "node-b", "node-c"));
        RecordingLifecycleListener lifecycle = new RecordingLifecycleListener();
        TenantCoreIsolationService tenantIsolation = org.mockito.Mockito.mock(TenantCoreIsolationService.class);
        org.mockito.Mockito.when(tenantIsolation.isolatedTenantIds()).thenReturn(List.of());
        CorePartitionManager manager = new CorePartitionManager(
                settings,
                transport,
                discovery,
                tenantIsolation,
                event -> {},
                List.of(lifecycle));

        manager.refresh("tb_core.main");
        discovery.setNodes(List.of("node-a", "node-b"));
        manager.refresh("tb_core.main");

        assertTrue(lifecycle.removedCount() > 0);
    }

    private QueueConsumerManager<QueueMessage> manager(
            InMemoryQueueStorage storage,
            QueueDefinition definition,
            QueueConsumerManager.MsgPackProcessor<QueueMessage> processor) {
        return QueueConsumerManager.<QueueMessage>builder()
                .queueKey("Main")
                .config(definition.toTransportConfig())
                .consumerCreator((cfg, partition) ->
                        new InMemoryQueueConsumer(storage, cfg, CONSUMER_GROUP))
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(processor)
                .build();
    }

    private void initExecutors() {
        consumerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "revoke-consumer"));
        workerExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "revoke-worker"));
    }

    private void shutdown(QueueConsumerManager<?>... managers) {
        for (QueueConsumerManager<?> manager : managers) {
            if (manager != null) {
                manager.stop();
                manager.awaitStop();
            }
        }
    }

    private void shutdownExecutors() {
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    private static QueueDefinition definition(UUID id, String topic) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(2)
                .pollInterval(20)
                .consumerPerPartition(true)
                .packProcessingTimeout(5_000)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
    }

    private static QueueDefinition retryDefinition(UUID id, String topic) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .name("Main")
                .topic(topic)
                .partitions(1)
                .pollInterval(20)
                .consumerPerPartition(true)
                .packProcessingTimeout(5_000)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.RETRY_FAILED, 1, 0, 1, 1))
                .build();
    }

    private static QueueMessage msg(String key) {
        return new DefaultQueueMessage(key, key.getBytes(StandardCharsets.UTF_8), null);
    }

    private static final class StubDiscovery implements ClusterNodeDiscovery {
        private List<String> nodes;

        StubDiscovery(List<String> nodes) {
            this.nodes = nodes;
        }

        void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }

        @Override
        public void register() {
        }

        @Override
        public void heartbeat() {
        }

        @Override
        public void deregister() {
        }

        @Override
        public List<String> sortedNodes() {
            return nodes;
        }
    }

    private static final class RecordingLifecycleListener implements PartitionLifecycleListener {
        private int removed;

        @Override
        public void onPartitionsRemoved(
                com.roseboard.infrastructure.transport.cluster.ClusterServiceType serviceType,
                Set<TopicPartitionInfo> partitions) {
            removed += partitions.size();
        }

        int removedCount() {
            return removed;
        }
    }
}
