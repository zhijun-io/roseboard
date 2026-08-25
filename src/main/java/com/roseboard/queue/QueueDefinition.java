package com.roseboard.queue;

import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.QueueTransportConfig;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.UUID;

public final class QueueDefinition {
    public static final String MAIN_QUEUE_NAME = "Main";

    private final UUID id;
    private final long createdTime;
    private final UUID tenantId;
    private final String name;
    private final String topic;
    private final int pollInterval;
    private final int partitions;
    private final boolean consumerPerPartition;
    private final long packProcessingTimeout;
    private final SubmitStrategy submitStrategy;
    private final ProcessingStrategy processingStrategy;
    private final JsonNode additionalInfo;

    private QueueDefinition(Builder builder) {
        this.id = builder.id;
        this.createdTime = builder.createdTime;
        this.tenantId = builder.tenantId;
        this.name = builder.name;
        this.topic = builder.topic;
        this.pollInterval = builder.pollInterval;
        this.partitions = builder.partitions;
        this.consumerPerPartition = builder.consumerPerPartition;
        this.packProcessingTimeout = builder.packProcessingTimeout;
        this.submitStrategy = builder.submitStrategy;
        this.processingStrategy = builder.processingStrategy;
        this.additionalInfo = builder.additionalInfo == null || builder.additionalInfo.isNull()
                ? JsonNodeFactory.instance.objectNode()
                : builder.additionalInfo;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID id() { return id; }
    public long createdTime() { return createdTime; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public String topic() { return topic; }
    public int pollInterval() { return pollInterval; }
    public int partitions() { return partitions; }
    public boolean consumerPerPartition() { return consumerPerPartition; }
    public long packProcessingTimeout() { return packProcessingTimeout; }
    public SubmitStrategy submitStrategy() { return submitStrategy; }
    public ProcessingStrategy processingStrategy() { return processingStrategy; }
    public JsonNode additionalInfo() { return additionalInfo; }

    public QueueTransportConfig toTransportConfig() {
        return new QueueTransportConfig(
                id,
                tenantId,
                name,
                topic,
                pollInterval,
                partitions,
                consumerPerPartition,
                packProcessingTimeout,
                submitStrategy,
                processingStrategy);
    }

    public Builder toBuilder() {
        return builder()
                .id(id)
                .createdTime(createdTime)
                .tenantId(tenantId)
                .name(name)
                .topic(topic)
                .pollInterval(pollInterval)
                .partitions(partitions)
                .consumerPerPartition(consumerPerPartition)
                .packProcessingTimeout(packProcessingTimeout)
                .submitStrategy(submitStrategy)
                .processingStrategy(processingStrategy)
                .additionalInfo(additionalInfo);
    }

    public static final class Builder {
        private UUID id;
        private long createdTime;
        private UUID tenantId;
        private String name;
        private String topic;
        private int pollInterval = 25;
        private int partitions = 10;
        private boolean consumerPerPartition;
        private long packProcessingTimeout = 2000L;
        private SubmitStrategy submitStrategy;
        private ProcessingStrategy processingStrategy;
        private JsonNode additionalInfo;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder createdTime(long createdTime) { this.createdTime = createdTime; return this; }
        public Builder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder pollInterval(int pollInterval) { this.pollInterval = pollInterval; return this; }
        public Builder partitions(int partitions) { this.partitions = partitions; return this; }
        public Builder consumerPerPartition(boolean consumerPerPartition) {
            this.consumerPerPartition = consumerPerPartition;
            return this;
        }
        public Builder packProcessingTimeout(long packProcessingTimeout) {
            this.packProcessingTimeout = packProcessingTimeout;
            return this;
        }
        public Builder submitStrategy(SubmitStrategy submitStrategy) {
            this.submitStrategy = submitStrategy;
            return this;
        }
        public Builder processingStrategy(ProcessingStrategy processingStrategy) {
            this.processingStrategy = processingStrategy;
            return this;
        }
        public Builder duplicateMsgToAllPartitions(boolean duplicate) {
            ObjectNode info = additionalInfo == null || !additionalInfo.isObject()
                    ? JsonNodeFactory.instance.objectNode()
                    : (ObjectNode) additionalInfo;
            info.put("duplicateMsgToAllPartitions", duplicate);
            this.additionalInfo = info;
            return this;
        }
        public Builder additionalInfo(JsonNode additionalInfo) {
            this.additionalInfo = additionalInfo;
            return this;
        }

        public QueueDefinition build() {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(submitStrategy, "submitStrategy");
            Objects.requireNonNull(processingStrategy, "processingStrategy");
            return new QueueDefinition(this);
        }
    }
}
