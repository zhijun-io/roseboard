package com.roseboard.queue;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

final class QueueConverter {
    private QueueConverter() {
    }

    static QueueDefinition toDefinition(QueueEntity entity) {
        try {
            SubmitStrategy submit = JacksonUtils.OBJECT_MAPPER.readValue(
                    entity.getSubmitStrategy(), SubmitStrategy.class);
            ProcessingStrategy processing = JacksonUtils.OBJECT_MAPPER.readValue(
                    entity.getProcessingStrategy(), ProcessingStrategy.class);
            JsonNode additional = parseAdditionalInfo(entity.getAdditionalInfo());
            return QueueDefinition.builder()
                    .id(entity.getId())
                    .createdTime(entity.getCreatedTime() == null ? 0L : entity.getCreatedTime())
                    .tenantId(entity.getTenantId())
                    .name(entity.getName())
                    .topic(entity.getTopic())
                    .pollInterval(entity.getPollInterval() == null ? 0 : entity.getPollInterval())
                    .partitions(entity.getPartitions() == null ? 0 : entity.getPartitions())
                    .consumerPerPartition(Boolean.TRUE.equals(entity.getConsumerPerPartition()))
                    .packProcessingTimeout(entity.getPackProcessingTimeout() == null
                            ? 0L : entity.getPackProcessingTimeout())
                    .submitStrategy(submit)
                    .processingStrategy(processing)
                    .additionalInfo(additional)
                    .build();
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid persisted queue strategy JSON for id=" + entity.getId(), ex);
        }
    }

    static QueueEntity toEntity(QueueDefinition definition) {
        QueueEntity entity = new QueueEntity();
        entity.setId(definition.id());
        entity.setCreatedTime(definition.createdTime());
        entity.setTenantId(definition.tenantId());
        entity.setName(definition.name());
        entity.setTopic(definition.topic());
        entity.setPollInterval(definition.pollInterval());
        entity.setPartitions(definition.partitions());
        entity.setConsumerPerPartition(definition.consumerPerPartition());
        entity.setPackProcessingTimeout(definition.packProcessingTimeout());
        entity.setSubmitStrategy(JacksonUtils.writeValueAsString(definition.submitStrategy()));
        entity.setProcessingStrategy(JacksonUtils.writeValueAsString(definition.processingStrategy()));
        entity.setAdditionalInfo(JacksonUtils.writeValueAsString(definition.additionalInfo()));
        return entity;
    }

    static JsonNode parseAdditionalInfo(String raw) {
        if (raw == null || raw.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = JacksonUtils.OBJECT_MAPPER.readTree(raw);
            if (node == null || node.isNull()) {
                return JsonNodeFactory.instance.objectNode();
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException("Queue additional_info must be a JSON object");
            }
            return node;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Queue additional_info is not valid JSON", ex);
        }
    }
}
