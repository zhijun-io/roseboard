/*
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Roseboard: partition lifecycle + async poll/commit loop in one manager.
 */
package com.roseboard.infrastructure.queue.consumer;

import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Queue consumer lifecycle used by Spring {@code QueueCoordinator}.
 */
public class QueueConsumerManager<M extends QueueMessage> {

    @FunctionalInterface
    public interface MsgPackProcessor<M extends QueueMessage> {
        CompletionStage<Void> process(List<M> msgs);
    }

    public enum LifecycleState {
        RUNNING,
        STOPPING,
        STOPPED
    }

    protected final Object queueKey;
    protected QueueTransportConfig config;
    protected final MsgPackProcessor<M> msgPackProcessor;
    protected final BiFunction<QueueTransportConfig, TopicPartitionInfo, QueueConsumer<M>> consumerCreator;
    protected final ExecutorService consumerExecutor;
    protected final ExecutorService workerExecutor;

    protected volatile ConsumerWrapper<M> consumerWrapper;
    protected volatile boolean stopped;
    private volatile LifecycleState lifecycleState = LifecycleState.STOPPED;

    protected QueueConsumerManager(Builder<M> builder) {
        this.queueKey = builder.queueKey;
        this.config = builder.config;
        this.msgPackProcessor = builder.msgPackProcessor;
        this.consumerCreator = builder.consumerCreator;
        this.consumerExecutor = builder.consumerExecutor;
        this.workerExecutor = builder.workerExecutor;
        if (config != null) {
            init(config);
        }
    }

    public static <M extends QueueMessage> Builder<M> builder() {
        return new Builder<>();
    }

    public QueueTransportConfig config() {
        return config;
    }

    public LifecycleState lifecycleState() {
        return lifecycleState;
    }

    public void init(QueueTransportConfig config) {
        this.config = config;
        this.consumerWrapper = config.consumerPerPartition()
                ? new ConsumerPerPartitionWrapper()
                : new SingleConsumerWrapper();
        this.lifecycleState = LifecycleState.RUNNING;
    }

    public void update(Set<TopicPartitionInfo> partitions) {
        if (stopped || consumerWrapper == null) {
            return;
        }
        consumerWrapper.updatePartitions(partitions);
    }

    private PartitionRunner<M> createRunner(ConsumerKey key, TopicPartitionInfo tpi) {
        QueueConsumer<M> consumer = consumerCreator.apply(config, tpi);
        return PartitionRunner.<M>builder()
                .pollInterval(config.pollInterval())
                .packProcessingTimeoutMs(config.packProcessingTimeout())
                .batchSubmit(config.submitStrategy().type() == SubmitStrategyType.BATCH)
                .batchSize(config.submitStrategy().batchSize())
                .consumer(consumer)
                .consumerExecutor(consumerExecutor)
                .workerExecutor(workerExecutor)
                .msgPackProcessor(msgPackProcessor)
                .build();
    }

    public void stop() {
        if (consumerWrapper != null) {
            consumerWrapper.getConsumers().forEach(ManagedConsumer::initiateStop);
        }
        stopped = true;
        lifecycleState = LifecycleState.STOPPING;
    }

    public void awaitStop() {
        awaitStop(30);
    }

    public void awaitStop(int timeoutSec) {
        if (consumerWrapper != null) {
            consumerWrapper.getConsumers().forEach(task -> task.awaitCompletion(timeoutSec));
        }
        lifecycleState = LifecycleState.STOPPED;
    }

    private static final class ManagedConsumer<M extends QueueMessage> {
        private final Supplier<PartitionRunner<M>> runnerSupplier;
        private volatile PartitionRunner<M> runner;
        private volatile boolean launched;

        private ManagedConsumer(Supplier<PartitionRunner<M>> runnerSupplier) {
            this.runnerSupplier = Objects.requireNonNull(runnerSupplier, "runnerSupplier");
        }

        private PartitionRunner<M> getRunner() {
            if (runner == null) {
                synchronized (this) {
                    if (runner == null) {
                        runner = Objects.requireNonNull(runnerSupplier.get(), "runner");
                    }
                }
            }
            return runner;
        }

        private void subscribe(Set<TopicPartitionInfo> partitions) {
            getRunner().subscribe(partitions);
        }

        private void launch() {
            getRunner().launch();
            launched = true;
        }

        private void initiateStop() {
            if (runner != null) {
                runner.stop();
            }
        }

        private void awaitCompletion(int timeoutSec) {
            if (runner != null) {
                runner.awaitStop(timeoutSec);
            }
            launched = false;
        }

        private boolean isRunning() {
            return launched;
        }
    }

    record ConsumerKey(Object queueKey, TopicPartitionInfo partition) {
        @Override
        public String toString() {
            if (partition != null) {
                Integer partitionId = partition.getPartition().orElse(-1);
                return queueKey + "-" + partitionId;
            }
            return queueKey.toString();
        }
    }

    interface ConsumerWrapper<M extends QueueMessage> {
        void updatePartitions(Set<TopicPartitionInfo> partitions);

        Collection<ManagedConsumer<M>> getConsumers();
    }

    final class ConsumerPerPartitionWrapper implements ConsumerWrapper<M> {
        private final Map<TopicPartitionInfo, ManagedConsumer<M>> consumers = new HashMap<>();

        @Override
        public void updatePartitions(Set<TopicPartitionInfo> partitions) {
            Set<TopicPartitionInfo> added = new HashSet<>(partitions);
            added.removeAll(consumers.keySet());
            Set<TopicPartitionInfo> removed = new HashSet<>(consumers.keySet());
            removed.removeAll(partitions);
            removed.forEach(tpi -> Optional.ofNullable(consumers.get(tpi))
                    .ifPresent(ManagedConsumer::initiateStop));
            removed.forEach(tpi -> Optional.ofNullable(consumers.remove(tpi))
                    .ifPresent(c -> c.awaitCompletion(30)));
            added.forEach(tpi -> {
                ManagedConsumer<M> consumer = new ManagedConsumer<>(() -> createRunner(new ConsumerKey(queueKey, tpi), tpi));
                consumers.put(tpi, consumer);
                consumer.subscribe(Set.of(tpi));
                consumer.launch();
            });
        }

        @Override
        public Collection<ManagedConsumer<M>> getConsumers() {
            return consumers.values();
        }
    }

    final class SingleConsumerWrapper implements ConsumerWrapper<M> {
        private ManagedConsumer<M> consumer;

        @Override
        public void updatePartitions(Set<TopicPartitionInfo> partitions) {
            if (partitions.isEmpty()) {
                if (consumer != null && consumer.isRunning()) {
                    consumer.initiateStop();
                    consumer.awaitCompletion(30);
                }
                consumer = null;
                return;
            }
            if (consumer == null) {
                consumer = new ManagedConsumer<>(() -> createRunner(new ConsumerKey(queueKey, null), null));
            }
            consumer.subscribe(partitions);
            if (!consumer.isRunning()) {
                consumer.launch();
            }
        }

        @Override
        public Collection<ManagedConsumer<M>> getConsumers() {
            return consumer == null ? Collections.emptyList() : List.of(consumer);
        }
    }

    /**
     * Poll loop for one {@link QueueConsumer}; used directly in unit tests.
     */
    public static final class PartitionRunner<M extends QueueMessage> {

        public enum RunnerState {
            RUNNING,
            STOPPING,
            STOPPED,
            FAILED
        }

        private final MsgPackProcessor<M> msgPackProcessor;
        private final long pollInterval;
        private final QueueConsumer<M> consumer;
        private final ExecutorService consumerExecutor;
        private final ExecutorService workerExecutor;
        private final long packProcessingTimeoutMs;
        private final boolean batchSubmit;
        private final int batchSize;

        private final ConcurrentLinkedQueue<Runnable> pollThreadCommands = new ConcurrentLinkedQueue<>();
        private final AtomicReference<RunnerState> state = new AtomicReference<>(RunnerState.STOPPED);

        private volatile boolean stopped;
        private volatile boolean stopRequested;
        private volatile boolean abandonInFlight;
        private volatile boolean batchInFlight;
        private Future<?> consumerTask;

        private PartitionRunner(PartitionBuilder<M> builder) {
            this.msgPackProcessor = Objects.requireNonNull(builder.msgPackProcessor, "msgPackProcessor");
            this.pollInterval = builder.pollInterval;
            this.packProcessingTimeoutMs = builder.packProcessingTimeoutMs;
            this.batchSubmit = builder.batchSubmit;
            this.batchSize = builder.batchSize;
            this.consumer = Objects.requireNonNull(builder.consumer, "consumer");
            this.consumerExecutor = Objects.requireNonNull(builder.consumerExecutor, "consumerExecutor");
            this.workerExecutor = Objects.requireNonNull(builder.workerExecutor, "workerExecutor");
        }

        public static <M extends QueueMessage> PartitionBuilder<M> builder() {
            return new PartitionBuilder<>();
        }

        public RunnerState state() {
            return state.get();
        }

        public void subscribe(Set<TopicPartitionInfo> partitions) {
            consumer.subscribe(partitions);
        }

        public void launch() {
            if (!state.compareAndSet(RunnerState.STOPPED, RunnerState.RUNNING)
                    && state.get() != RunnerState.RUNNING) {
                if (state.get() == RunnerState.FAILED) {
                    throw new IllegalStateException("Runner is FAILED; reconstruct before launch");
                }
            }
            state.set(RunnerState.RUNNING);
            stopped = false;
            stopRequested = false;
            abandonInFlight = false;
            consumerTask = consumerExecutor.submit(() -> {
                try {
                    consumerLoop();
                } catch (Throwable t) {
                    enterFailed(t);
                }
            });
        }

        private void consumerLoop() {
            List<M> pendingBatch = new ArrayList<>();
            long pendingSince = 0;
            while (!stopped && !consumer.isStopped() && state.get() != RunnerState.FAILED) {
                try {
                    drainPollThreadCommands();
                    if (state.get() == RunnerState.FAILED) {
                        return;
                    }
                    if (stopRequested && !batchInFlight) {
                        if (pendingBatch.isEmpty()) {
                            break;
                        }
                        List<M> batch = List.copyOf(pendingBatch);
                        pendingBatch.clear();
                        batchInFlight = true;
                        workerExecutor.execute(() -> processBatch(batch));
                        continue;
                    }
                    if (batchInFlight || stopRequested) {
                        consumer.poll(pollInterval);
                        drainPollThreadCommands();
                        continue;
                    }
                    List<M> msgs = consumer.poll(pollInterval);
                    drainPollThreadCommands();
                    if (state.get() == RunnerState.FAILED || batchInFlight || stopRequested) {
                        continue;
                    }
                    if (msgs.isEmpty()) {
                        if (batchSubmit && !pendingBatch.isEmpty()
                                && elapsedMillis(pendingSince) >= pollInterval) {
                            submitPendingBatch(pendingBatch);
                            pendingBatch.clear();
                            pendingSince = 0;
                        }
                        continue;
                    }
                    if (!batchSubmit) {
                        batchInFlight = true;
                        workerExecutor.execute(() -> processBatch(msgs));
                        continue;
                    }
                    if (pendingBatch.isEmpty()) {
                        pendingSince = System.nanoTime();
                    }
                    pendingBatch.addAll(msgs);
                    if (pendingBatch.size() >= batchSize) {
                        submitPendingBatch(pendingBatch);
                        pendingBatch.clear();
                        pendingSince = 0;
                    }
                } catch (Exception e) {
                    if (!consumer.isStopped() && state.get() != RunnerState.FAILED) {
                        sleepQuietly(pollInterval);
                    }
                }
            }
            drainPollThreadCommands();
        }

        private void submitPendingBatch(List<M> pendingBatch) {
            batchInFlight = true;
            List<M> batch = List.copyOf(pendingBatch);
            workerExecutor.execute(() -> processBatch(batch));
        }

        private static long elapsedMillis(long startedNanos) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }

        private void processBatch(List<M> batch) {
            try {
                CompletionStage<Void> stage = msgPackProcessor.process(batch);
                stage.whenComplete((ignored, error) ->
                        pollThreadCommands.add(() -> onBatchCompleted(error)));
            } catch (Throwable t) {
                pollThreadCommands.add(() -> onBatchCompleted(t));
            }
        }

        private void onBatchCompleted(Throwable error) {
            if (abandonInFlight) {
                batchInFlight = false;
                return;
            }
            if (error != null) {
                batchInFlight = false;
                enterFailed(error);
                return;
            }
            if (state.get() == RunnerState.FAILED) {
                return;
            }
            try {
                consumer.commit();
                batchInFlight = false;
            } catch (Throwable t) {
                enterFailed(t);
            }
        }

        private void drainPollThreadCommands() {
            Runnable command;
            while ((command = pollThreadCommands.poll()) != null) {
                command.run();
            }
        }

        private void enterFailed(Throwable t) {
            state.set(RunnerState.FAILED);
            stopped = true;
        }

        public void stop() {
            if (state.get() == RunnerState.STOPPED) {
                return;
            }
            if (state.get() != RunnerState.FAILED) {
                state.set(RunnerState.STOPPING);
            }
            stopRequested = true;
        }

        public void awaitStop(int timeoutSec) {
            awaitStop(TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSec)));
        }

        private void awaitStop(long waitMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, waitMs));
            long packDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, packProcessingTimeoutMs));
            while (batchInFlight
                    && System.nanoTime() < deadline
                    && System.nanoTime() < packDeadline
                    && state.get() != RunnerState.FAILED) {
                sleepQuietly(Math.min(20, pollInterval));
            }
            if (batchInFlight) {
                abandonInFlight = true;
                batchInFlight = false;
            }
            stopped = true;
            try {
                consumer.stop();
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                if (consumerTask != null) {
                    long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    consumerTask.get(remainingMs, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                consumer.unsubscribe();
            } catch (Exception ignored) {
                // best-effort
            }
            if (state.get() != RunnerState.FAILED) {
                state.set(RunnerState.STOPPED);
            }
            consumerTask = null;
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(Math.max(1, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public static final class PartitionBuilder<M extends QueueMessage> {
            private MsgPackProcessor<M> msgPackProcessor;
            private long pollInterval = 25;
            private long packProcessingTimeoutMs = 30_000;
            private boolean batchSubmit;
            private int batchSize = 1;
            private QueueConsumer<M> consumer;
            private ExecutorService consumerExecutor;
            private ExecutorService workerExecutor;

            public PartitionBuilder<M> msgPackProcessor(MsgPackProcessor<M> msgPackProcessor) {
                this.msgPackProcessor = msgPackProcessor;
                return this;
            }

            public PartitionBuilder<M> pollInterval(long pollInterval) {
                this.pollInterval = pollInterval;
                return this;
            }

            public PartitionBuilder<M> packProcessingTimeoutMs(long packProcessingTimeoutMs) {
                this.packProcessingTimeoutMs = packProcessingTimeoutMs;
                return this;
            }

            public PartitionBuilder<M> batchSubmit(boolean batchSubmit) {
                this.batchSubmit = batchSubmit;
                return this;
            }

            public PartitionBuilder<M> batchSize(int batchSize) {
                this.batchSize = Math.max(1, batchSize);
                return this;
            }

            public PartitionBuilder<M> consumer(QueueConsumer<M> consumer) {
                this.consumer = consumer;
                return this;
            }

            public PartitionBuilder<M> consumerExecutor(ExecutorService consumerExecutor) {
                this.consumerExecutor = consumerExecutor;
                return this;
            }

            public PartitionBuilder<M> workerExecutor(ExecutorService workerExecutor) {
                this.workerExecutor = workerExecutor;
                return this;
            }

            public PartitionRunner<M> build() {
                return new PartitionRunner<>(this);
            }
        }
    }

    public static class Builder<M extends QueueMessage> {
        private Object queueKey;
        private QueueTransportConfig config;
        private MsgPackProcessor<M> msgPackProcessor;
        private BiFunction<QueueTransportConfig, TopicPartitionInfo, QueueConsumer<M>> consumerCreator;
        private ExecutorService consumerExecutor;
        private ExecutorService workerExecutor;

        public Builder<M> queueKey(Object queueKey) {
            this.queueKey = queueKey;
            return this;
        }

        public Builder<M> config(QueueTransportConfig config) {
            this.config = config;
            return this;
        }

        public Builder<M> msgPackProcessor(MsgPackProcessor<M> msgPackProcessor) {
            this.msgPackProcessor = msgPackProcessor;
            return this;
        }

        public Builder<M> consumerCreator(
                BiFunction<QueueTransportConfig, TopicPartitionInfo, QueueConsumer<M>> consumerCreator) {
            this.consumerCreator = consumerCreator;
            return this;
        }

        public Builder<M> consumerExecutor(ExecutorService consumerExecutor) {
            this.consumerExecutor = consumerExecutor;
            return this;
        }

        public Builder<M> workerExecutor(ExecutorService workerExecutor) {
            this.workerExecutor = workerExecutor;
            return this;
        }

        public QueueConsumerManager<M> build() {
            return new QueueConsumerManager<>(this);
        }
    }
}
