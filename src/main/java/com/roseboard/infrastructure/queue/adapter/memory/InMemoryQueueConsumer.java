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
 * Modified for Roseboard: lease/commit/restore in-flight batch; Consumer Group required.
 */
package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class InMemoryQueueConsumer implements QueueConsumer<QueueMessage> {
    private static final int MAX_BATCH = 1000;

    private final InMemoryQueueStorage storage;
    private final UUID queueId;
    private final String topic;
    private final String consumerGroup;
    private volatile Set<TopicPartitionInfo> partitions = Set.of();
    private volatile boolean stopped;
    private volatile boolean subscribed;

    public InMemoryQueueConsumer(InMemoryQueueStorage storage, QueueTransportConfig config, String consumerGroup) {
        this.storage = Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(config, "config");
        this.queueId = Objects.requireNonNull(config.id(), "definition.id");
        this.topic = config.topic();
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup is required");
        }
        this.consumerGroup = consumerGroup;
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public void subscribe() {
        partitions = Set.of(new TopicPartitionInfo(topic, null, null, true));
        subscribed = true;
    }

    @Override
    public void subscribe(Set<TopicPartitionInfo> partitions) {
        this.partitions = partitions == null ? Set.of() : Set.copyOf(partitions);
        subscribed = true;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public void unsubscribe() {
        stopped = true;
        subscribed = false;
        for (TopicPartitionInfo tpi : partitions) {
            if (storage.hasInFlight(queueId, consumerGroup, tpi.getFullTopicName())) {
                storage.restore(queueId, consumerGroup, tpi.getFullTopicName());
            }
        }
    }

    @Override
    public List<QueueMessage> poll(long durationInMillis) {
        if (!subscribed || stopped) {
            sleepQuietly(durationInMillis);
            return List.of();
        }
        List<QueueMessage> messages = new ArrayList<>();
        for (TopicPartitionInfo tpi : partitions) {
            messages.addAll(storage.lease(queueId, consumerGroup, tpi.getFullTopicName(), MAX_BATCH));
        }
        if (!messages.isEmpty()) {
            return List.copyOf(messages);
        }
        sleepQuietly(durationInMillis);
        return List.of();
    }

    @Override
    public void commit() {
        for (TopicPartitionInfo tpi : partitions) {
            storage.commit(queueId, consumerGroup, tpi.getFullTopicName());
        }
    }

    public void restoreInFlight() {
        for (TopicPartitionInfo tpi : partitions) {
            storage.restore(queueId, consumerGroup, tpi.getFullTopicName());
        }
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public Set<TopicPartitionInfo> getPartitions() {
        return partitions;
    }

    @Override
    public List<String> getFullTopicNames() {
        return partitions.stream().map(TopicPartitionInfo::getFullTopicName).collect(Collectors.toList());
    }

    private static void sleepQuietly(long durationInMillis) {
        if (durationInMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(durationInMillis, 50));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
