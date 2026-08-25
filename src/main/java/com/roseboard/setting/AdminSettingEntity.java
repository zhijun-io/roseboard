package com.roseboard.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import jakarta.validation.constraints.NotBlank;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

@TableName(value = "admin_setting", autoResultMap = true)
public class AdminSettingEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID tenantId;
    private Long createdTime;
    @TableField(value = "value", typeHandler = Jackson3TypeHandler.class)
    private JsonNode value;
    @NotBlank
    private String key;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
}
