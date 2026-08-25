package com.roseboard.queue.stats;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

@TableName("queue_stats")
public class QueueStatsEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private String queueName;
    private String serviceId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
}
