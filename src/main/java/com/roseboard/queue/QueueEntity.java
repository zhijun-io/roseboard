package com.roseboard.queue;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

@TableName("queue")
public class QueueEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private String name;
    private String topic;
    private Integer pollInterval;
    private Integer partitions;
    private Boolean consumerPerPartition;
    private Long packProcessingTimeout;
    private String submitStrategy;
    private String processingStrategy;
    private String additionalInfo;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public Integer getPollInterval() { return pollInterval; }
    public void setPollInterval(Integer pollInterval) { this.pollInterval = pollInterval; }
    public Integer getPartitions() { return partitions; }
    public void setPartitions(Integer partitions) { this.partitions = partitions; }
    public Boolean getConsumerPerPartition() { return consumerPerPartition; }
    public void setConsumerPerPartition(Boolean consumerPerPartition) {
        this.consumerPerPartition = consumerPerPartition;
    }
    public Long getPackProcessingTimeout() { return packProcessingTimeout; }
    public void setPackProcessingTimeout(Long packProcessingTimeout) {
        this.packProcessingTimeout = packProcessingTimeout;
    }
    public String getSubmitStrategy() { return submitStrategy; }
    public void setSubmitStrategy(String submitStrategy) { this.submitStrategy = submitStrategy; }
    public String getProcessingStrategy() { return processingStrategy; }
    public void setProcessingStrategy(String processingStrategy) {
        this.processingStrategy = processingStrategy;
    }
    public String getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(String additionalInfo) { this.additionalInfo = additionalInfo; }
}
