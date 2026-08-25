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
 * Modified for Roseboard naming; TopicPartitionInfo is Roseboard routing type.
 */
package com.roseboard.infrastructure.queue.spi;

import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;

public interface QueueProducer<T extends QueueMessage> {
    String getDefaultTopic();

    void send(TopicPartitionInfo tpi, T msg, QueueCallback callback);

    default void sendToAllPartitions(String topic, int partitions, T msg, QueueCallback callback) {
        if (partitions < 1) {
            throw new IllegalArgumentException("partitions must be positive");
        }
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(partitions);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        QueueCallback aggregate = new QueueCallback() {
            @Override
            public void onSuccess() {
                if (remaining.decrementAndGet() == 0) {
                    if (failure.get() == null) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure(failure.get());
                    }
                }
            }

            @Override
            public void onFailure(Throwable error) {
                failure.compareAndSet(null, error);
                onSuccess();
            }
        };
        for (int partition = 0; partition < partitions; partition++) {
            send(new TopicPartitionInfo(topic, null, partition, false, true), msg, aggregate);
        }
    }

    void stop();
}
