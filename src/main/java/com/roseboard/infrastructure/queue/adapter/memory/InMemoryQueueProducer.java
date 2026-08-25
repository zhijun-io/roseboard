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
 * Modified for Roseboard: String keys, QueueDefinition.
 */
package com.roseboard.infrastructure.queue.adapter.memory;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;

import java.util.Objects;
import java.util.UUID;

public final class InMemoryQueueProducer implements QueueProducer<QueueMessage> {
    private final InMemoryQueueStorage storage;
    private final UUID queueId;
    private final String defaultTopic;
    private volatile boolean stopped;

    public InMemoryQueueProducer(InMemoryQueueStorage storage, QueueTransportConfig config) {
        this.storage = Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(config, "config");
        this.queueId = Objects.requireNonNull(config.id(), "definition.id");
        this.defaultTopic = config.topic();
    }

    @Override
    public String getDefaultTopic() {
        return defaultTopic;
    }

    @Override
    public void send(TopicPartitionInfo tpi, QueueMessage msg, QueueCallback callback) {
        QueueCallback once = QueueCallback.once(callback);
        if (stopped) {
            once.onFailure(new IllegalStateException("Memory producer is stopped"));
            return;
        }
        try {
            storage.put(queueId, tpi.getFullTopicName(), msg);
            once.onSuccess();
        } catch (RuntimeException ex) {
            once.onFailure(ex);
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
