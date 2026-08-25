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
 * Modified for Roseboard: String key; partition only when useInternalPartition=true.
 */
package com.roseboard.infrastructure.queue.adapter.kafka;

import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class KafkaQueueProducer implements QueueProducer<QueueMessage> {

    private final Producer<String, byte[]> producer;
    private final String defaultTopic;
    private final QueueAdmin admin;
    private final Set<String> topics = ConcurrentHashMap.newKeySet();
    private final String clientId;

    public KafkaQueueProducer(KafkaQueueProperties settings, String defaultTopic, String clientId, QueueAdmin admin) {
        this(settings, defaultTopic, clientId, admin, null);
    }

    KafkaQueueProducer(KafkaQueueProperties settings, String defaultTopic, String clientId, QueueAdmin admin,
                       Producer<String, byte[]> producerOverride) {
        Objects.requireNonNull(settings, "settings");
        this.defaultTopic = Objects.requireNonNull(defaultTopic, "defaultTopic");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.admin = Objects.requireNonNull(admin, "admin");
        if (producerOverride != null) {
            this.producer = producerOverride;
        } else {
            Properties props = settings.toProducerProps();
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
            this.producer = new KafkaProducer<>(props);
        }
    }

    @Override
    public String getDefaultTopic() {
        return defaultTopic;
    }

    public String clientId() {
        return clientId;
    }

    @Override
    public void send(TopicPartitionInfo tpi, QueueMessage msg, QueueCallback callback) {
        QueueCallback once = QueueCallback.once(callback);
        try {
            String topic = tpi.getFullTopicName();
            createTopicIfNotExist(topic);
            List<Header> headers = new ArrayList<>();
            if (msg.getHeaders() != null && !msg.getHeaders().isEmpty()) {
                msg.getHeaders().forEach((k, v) -> headers.add(new RecordHeader(k, v)));
            }
            Integer partition = tpi.isUseInternalPartition() ? tpi.getPartition().orElse(null) : null;
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(topic, partition, msg.getKey(), msg.getData(), headers);
            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    once.onSuccess();
                } else {
                    once.onFailure(exception);
                }
            });
        } catch (Exception e) {
            once.onFailure(e);
            throw e;
        }
    }

    private void createTopicIfNotExist(String topic) {
        if (topics.contains(topic)) {
            return;
        }
        admin.createTopicIfNotExists(topic);
        topics.add(topic);
    }

    public void flush() {
        producer.flush();
    }

    @Override
    public void stop() {
        producer.close();
    }
}
