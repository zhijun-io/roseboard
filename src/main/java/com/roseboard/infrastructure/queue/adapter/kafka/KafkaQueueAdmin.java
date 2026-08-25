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
 */
package com.roseboard.infrastructure.queue.adapter.kafka;

import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class KafkaQueueAdmin implements QueueAdmin {

    public static final String NUM_PARTITIONS_SETTING = "partitions";

    private final KafkaQueueProperties settings;
    private final AdminClient client;
    private final boolean ownsClient;
    private final Map<String, String> topicConfigs;
    private final int numPartitions;
    private final Set<String> topics = ConcurrentHashMap.newKeySet();

    public KafkaQueueAdmin(KafkaQueueProperties settings) {
        this(settings, AdminClient.create(settings.toAdminProps()), Map.of(NUM_PARTITIONS_SETTING, "1"), true);
    }

    KafkaQueueAdmin(KafkaQueueProperties settings, AdminClient client, Map<String, String> topicConfigs, boolean ownsClient) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.client = Objects.requireNonNull(client, "client");
        this.topicConfigs = topicConfigs == null ? Map.of() : Map.copyOf(topicConfigs);
        this.ownsClient = ownsClient;
        String partitions = this.topicConfigs.get(NUM_PARTITIONS_SETTING);
        this.numPartitions = partitions != null ? Integer.parseInt(partitions) : 1;
        refreshTopics();
    }

    public KafkaQueueAdmin withPartitions(int partitions) {
        return new KafkaQueueAdmin(settings, client,
                Map.of(NUM_PARTITIONS_SETTING, String.valueOf(Math.max(1, partitions))), false);
    }

    public int numPartitions() {
        return numPartitions;
    }

    public Set<String> listTopics() {
        try {
            Set<String> listed = client.listTopics().names().get(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            topics.clear();
            topics.addAll(listed);
            return Set.copyOf(listed);
        } catch (Exception e) {
            throw new RuntimeException("Failed to list topics", e);
        }
    }

    @Override
    public void createTopicIfNotExists(String topic, String properties, boolean force) {
        Map<String, String> merged = new LinkedHashMap<>(topicConfigs);
        if (properties != null && !properties.isBlank()) {
            merged.putAll(KafkaQueueProperties.parseInline(properties));
        }
        if (!force && topics.contains(topic)) {
            return;
        }
        Map<String, String> configs = new LinkedHashMap<>(merged);
        String numPartitionsStr = configs.remove(NUM_PARTITIONS_SETTING);
        int partitions = numPartitionsStr != null ? Integer.parseInt(numPartitionsStr) : numPartitions;
        NewTopic newTopic = new NewTopic(topic, partitions, settings.replicationFactor()).configs(configs);
        try {
            client.createTopics(List.of(newTopic)).all().get(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            topics.add(topic);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TopicExistsException) {
                topics.add(topic);
                return;
            }
            throw new RuntimeException("Failed to create topic " + topic, e);
        }
    }

    @Override
    public void deleteTopic(String topic) {
        try {
            client.deleteTopics(List.of(topic)).all().get(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            topics.remove(topic);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete topic " + topic, e);
        }
    }

    @Override
    public void destroy() {
        if (ownsClient) {
            client.close();
        }
    }

    private void refreshTopics() {
        try {
            topics.addAll(client.listTopics().names().get(settings.requestTimeoutMs(), TimeUnit.MILLISECONDS));
        } catch (Exception ignored) {
            // cache warms lazily
        }
    }
}
