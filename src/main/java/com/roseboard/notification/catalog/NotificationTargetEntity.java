package com.roseboard.notification.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "notification_target", autoResultMap = true)
public class NotificationTargetEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private Long updatedTime;
    private UUID tenantId;
    private String name;
    private String targetType;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode userIds;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode configuration;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Long createdTime) {
        this.createdTime = createdTime;
    }

    public Long getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(Long updatedTime) {
        this.updatedTime = updatedTime;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public JsonNode getUserIds() {
        return userIds;
    }

    public void setUserIds(JsonNode userIds) {
        this.userIds = userIds;
    }

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration;
    }
}
