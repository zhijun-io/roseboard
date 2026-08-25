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
 * Modified for Roseboard: factory for Kafka producer/consumer/admin.
 */
package com.roseboard.infrastructure.queue.adapter.kafka;

import com.roseboard.infrastructure.queue.spi.QueueConsumer;
import com.roseboard.infrastructure.queue.spi.QueueConsumerProvider;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KafkaQueueProvider implements QueueProducerProvider, QueueConsumerProvider {

    private final KafkaQueueProperties properties;
    private final KafkaQueueAdmin kafkaAdmin;
    private final Map<String, KafkaQueueAdmin> adminsByPartitions = new ConcurrentHashMap<>();

    public KafkaQueueProvider(KafkaQueueProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.kafkaAdmin = new KafkaQueueAdmin(properties);
    }

    public KafkaQueueProperties properties() {
        return properties;
    }

    public QueueAdmin admin() {
        return kafkaAdmin;
    }

    public KafkaQueueAdmin adminForPartitions(int partitions) {
        return adminsByPartitions.computeIfAbsent(String.valueOf(partitions),
                ignored -> kafkaAdmin.withPartitions(Math.max(1, partitions)));
    }

    public KafkaQueueAdmin kafkaAdmin() {
        return kafkaAdmin;
    }

    @Override
    public QueueProducer<QueueMessage> createProducer(QueueTransportConfig config) {
        return new KafkaQueueProducer(properties, config.topic(),
                "rb-prod-" + config.id(), adminForPartitions(config.partitions()));
    }

    public QueueProducer<QueueMessage> createProducer(String defaultTopic, String clientId, int partitions) {
        return new KafkaQueueProducer(properties, defaultTopic, clientId, adminForPartitions(partitions));
    }

    @Override
    public QueueConsumer<QueueMessage> createConsumer(QueueTransportConfig config, String consumerGroup) {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup is required");
        }
        return new KafkaQueueConsumer(properties, config.topic(),
                "rb-cons-" + config.id() + "-" + UUID.randomUUID(),
                consumerGroup, adminForPartitions(config.partitions()));
    }

    public void destroy() {
        kafkaAdmin.destroy();
    }
}
