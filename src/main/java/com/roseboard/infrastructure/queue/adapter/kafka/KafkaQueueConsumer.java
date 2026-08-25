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
 * Modified for Roseboard: exact String keys; pause after business poll; generation tracking.
 */
package com.roseboard.infrastructure.queue.adapter.kafka;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class KafkaQueueConsumer implements QueueConsumer<QueueMessage> {

    public enum ConsumerState {
        RUNNING,
        FAILED,
        STOPPED
    }

    private final String topic;
    private final String groupId;
    private final KafkaQueueAdmin admin;
    private final ConsumerOps consumer;
    private final Queue<Set<TopicPartitionInfo>> subscribeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger assignmentGeneration = new AtomicInteger();
    private final AtomicReference<ConsumerState> state = new AtomicReference<>(ConsumerState.RUNNING);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private volatile Set<TopicPartitionInfo> partitions = Set.of();
    private volatile boolean subscribed;
    private volatile boolean stopped;
    private volatile boolean pausedForInFlight;
    private volatile int inFlightGeneration = -1;
    private volatile Set<TopicPartition> inFlightAssignment = Set.of();

    public KafkaQueueConsumer(KafkaQueueProperties settings, String topic, String clientId, String groupId,
                              KafkaQueueAdmin admin) {
        this(settings, topic, clientId, groupId, admin, (ConsumerOps) null);
    }

    public KafkaQueueConsumer(KafkaQueueProperties settings, String topic, String clientId, String groupId,
                              KafkaQueueAdmin admin, ConsumerOps consumerOverride) {
        this.topic = Objects.requireNonNull(topic, "topic");
        this.groupId = groupId;
        this.admin = Objects.requireNonNull(admin, "admin");
        if (consumerOverride != null) {
            this.consumer = consumerOverride;
        } else {
            Properties props = settings.toConsumerProps(topic);
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, Objects.requireNonNull(clientId, "clientId"));
            if (groupId != null) {
                props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            }
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            this.consumer = ConsumerOps.wrap(new KafkaConsumer<>(props));
        }
    }

    public ConsumerState state() {
        return state.get();
    }

    public Throwable failure() {
        return failure.get();
    }

    public int assignmentGeneration() {
        return assignmentGeneration.get();
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void subscribe() {
        subscribe(Set.of(new TopicPartitionInfo(topic, null, null, true)));
    }

    @Override
    public void subscribe(Set<TopicPartitionInfo> partitions) {
        if (stopped) {
            return;
        }
        subscribeQueue.add(partitions == null ? Set.of() : Set.copyOf(partitions));
    }

    @Override
    public void stop() {
        stopped = true;
        state.compareAndSet(ConsumerState.RUNNING, ConsumerState.STOPPED);
    }

    @Override
    public void unsubscribe() {
        stopped = true;
        try {
            consumer.unsubscribe();
            consumer.close();
        } catch (Exception ignored) {
            // best-effort
        }
        state.compareAndSet(ConsumerState.RUNNING, ConsumerState.STOPPED);
    }

    @Override
    public List<QueueMessage> poll(long durationInMillis) {
        if (stopped || state.get() == ConsumerState.FAILED) {
            return List.of();
        }
        applyPendingSubscribe();
        if (!subscribed || partitions.isEmpty()) {
            sleepQuietly(Math.min(durationInMillis, 50));
            return List.of();
        }
        if (pausedForInFlight) {
            consumer.poll(Duration.ofMillis(Math.max(1, durationInMillis)));
            return List.of();
        }
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(Math.max(1, durationInMillis)));
        if (records.isEmpty()) {
            return List.of();
        }
        List<QueueMessage> messages = new ArrayList<>();
        Set<TopicPartition> assigned = new HashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            assigned.add(new TopicPartition(record.topic(), record.partition()));
            messages.add(decode(record));
        }
        pausedForInFlight = true;
        inFlightGeneration = assignmentGeneration.get();
        inFlightAssignment = Set.copyOf(assigned);
        consumer.pause(assigned);
        return List.copyOf(messages);
    }

    @Override
    public void commit() {
        commit(inFlightGeneration);
    }

    public void commit(int generation) {
        if (state.get() == ConsumerState.FAILED) {
            return;
        }
        if (generation != inFlightGeneration || generation < 0) {
            return;
        }
        try {
            if (groupId != null) {
                consumer.commitSync();
            }
            if (!inFlightAssignment.isEmpty()) {
                consumer.resume(inFlightAssignment);
            }
            pausedForInFlight = false;
            inFlightAssignment = Set.of();
            inFlightGeneration = -1;
        } catch (RuntimeException e) {
            enterFailed(e);
            throw e;
        }
    }

    public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
        assignmentGeneration.incrementAndGet();
        if (pausedForInFlight) {
            inFlightGeneration = -1;
            pausedForInFlight = false;
            inFlightAssignment = Set.of();
        }
        try {
            consumer.resume(revoked);
        } catch (Exception ignored) {
            // may already be unassigned
        }
    }

    private void enterFailed(Throwable t) {
        failure.compareAndSet(null, t);
        state.set(ConsumerState.FAILED);
        stopped = true;
        try {
            consumer.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void applyPendingSubscribe() {
        while (!subscribeQueue.isEmpty()) {
            partitions = subscribeQueue.poll();
            subscribed = false;
        }
        if (!subscribed) {
            doSubscribe(partitions);
            subscribed = true;
            assignmentGeneration.incrementAndGet();
        }
    }

    private void doSubscribe(Set<TopicPartitionInfo> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            consumer.unsubscribe();
            return;
        }
        Map<String, List<Integer>> topics = new HashMap<>();
        partitions.forEach(tpi -> {
            if (tpi.isUseInternalPartition()) {
                topics.computeIfAbsent(tpi.getFullTopicName(), t -> new ArrayList<>())
                        .add(tpi.getPartition().orElseThrow());
            } else {
                topics.put(tpi.getFullTopicName(), null);
            }
        });
        topics.keySet().forEach(admin::createTopicIfNotExists);
        List<String> toSubscribe = new ArrayList<>();
        topics.forEach((topicName, kafkaPartitions) -> {
            if (kafkaPartitions == null) {
                if (groupId != null) {
                    toSubscribe.add(topicName);
                } else {
                    List<TopicPartition> tps = new ArrayList<>();
                    for (int i = 0; i < admin.numPartitions(); i++) {
                        tps.add(new TopicPartition(topicName, i));
                    }
                    consumer.assign(tps);
                }
            } else {
                List<TopicPartition> tps = kafkaPartitions.stream()
                        .map(p -> new TopicPartition(topicName, p))
                        .toList();
                consumer.assign(tps);
            }
        });
        if (!toSubscribe.isEmpty()) {
            consumer.subscribe(toSubscribe, new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    KafkaQueueConsumer.this.onPartitionsRevoked(partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    assignmentGeneration.incrementAndGet();
                }
            });
        }
    }

    private static QueueMessage decode(ConsumerRecord<String, byte[]> record) {
        Map<String, byte[]> headers = new HashMap<>();
        record.headers().forEach(h -> headers.put(h.key(), h.value()));
        headers.put(QueueMessage.SOURCE_TOPIC_HEADER,
                record.topic().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.put(QueueMessage.SOURCE_PARTITION_HEADER,
                Integer.toString(record.partition()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new DefaultQueueMessage(record.key(), record.value(), headers);
    }

    @Override
    public boolean isStopped() {
        return stopped || state.get() != ConsumerState.RUNNING;
    }

    @Override
    public Set<TopicPartitionInfo> getPartitions() {
        return partitions;
    }

    @Override
    public List<String> getFullTopicNames() {
        return partitions.stream().map(TopicPartitionInfo::getFullTopicName).collect(Collectors.toList());
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Narrow consumer surface for production wiring and test doubles.
     */
    public interface ConsumerOps {
        void assign(Collection<TopicPartition> partitions);

        void subscribe(Collection<String> topics, ConsumerRebalanceListener listener);

        void unsubscribe();

        ConsumerRecords<String, byte[]> poll(Duration timeout);

        void commitSync();

        void pause(Collection<TopicPartition> partitions);

        void resume(Collection<TopicPartition> partitions);

        void close();

        static ConsumerOps wrap(Consumer<String, byte[]> consumer) {
            return new ConsumerOps() {
                @Override
                public void assign(Collection<TopicPartition> partitions) {
                    consumer.assign(partitions);
                }

                @Override
                public void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
                    consumer.subscribe(topics, listener);
                }

                @Override
                public void unsubscribe() {
                    consumer.unsubscribe();
                }

                @Override
                public ConsumerRecords<String, byte[]> poll(Duration timeout) {
                    return consumer.poll(timeout);
                }

                @Override
                public void commitSync() {
                    consumer.commitSync();
                }

                @Override
                public void pause(Collection<TopicPartition> partitions) {
                    consumer.pause(partitions);
                }

                @Override
                public void resume(Collection<TopicPartition> partitions) {
                    consumer.resume(partitions);
                }

                @Override
                public void close() {
                    consumer.close();
                }
            };
        }
    }
}
