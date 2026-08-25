package com.roseboard.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * audit_log 查询持久化实体。它保存审计事实、租户范围快照和运行环境关联信息，
 * 不保存审计写入策略或异常堆栈。
 */
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLogEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Instant occurredAt;
    private UUID actorUserId;
    private String actorName;
    private UUID tenantId;
    private UUID customerId;
    private String action;
    private EntityType entityType;
    private UUID entityId;
    private String entityName;
    private AuditStatus status;
    private AuditOrigin origin;
    private String requestId;
    private String failureMessage;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode detail;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode context;
    private Long durationMs;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public AuditStatus getStatus() { return status; }
    public void setStatus(AuditStatus status) { this.status = status; }
    public AuditOrigin getOrigin() { return origin; }
    public void setOrigin(AuditOrigin origin) { this.origin = origin; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public JsonNode getDetail() { return detail; }
    public void setDetail(JsonNode detail) { this.detail = detail; }
    public JsonNode getContext() { return context; }
    public void setContext(JsonNode context) { this.context = context; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
