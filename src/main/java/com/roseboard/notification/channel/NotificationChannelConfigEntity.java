package com.roseboard.notification.channel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "notification_channel_config", autoResultMap = true)
public class NotificationChannelConfigEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID tenantId;
    private String channelKind;
    private Boolean enabled;
    private String connectionMode;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode config;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode secrets;
    private Long createdTime;
    private Long updatedTime;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getChannelKind() {
        return channelKind;
    }

    public void setChannelKind(String channelKind) {
        this.channelKind = channelKind;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(String connectionMode) {
        this.connectionMode = connectionMode;
    }

    public JsonNode getConfig() {
        return config;
    }

    public void setConfig(JsonNode config) {
        this.config = config;
    }

    public JsonNode getSecrets() {
        return secrets;
    }

    public void setSecrets(JsonNode secrets) {
        this.secrets = secrets;
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
}
