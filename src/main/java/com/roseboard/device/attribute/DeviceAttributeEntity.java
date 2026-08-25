package com.roseboard.device.attribute;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "device_attribute", autoResultMap = true)
public class DeviceAttributeEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private UUID tenantId;
    private UUID deviceId;
    private AttributeScope scope;
    private String attributeKey;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode value;
    private Long version;
    private Long updatedTime;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public AttributeScope getScope() { return scope; }
    public void setScope(AttributeScope scope) { this.scope = scope; }
    public String getAttributeKey() { return attributeKey; }
    public void setAttributeKey(String attributeKey) { this.attributeKey = attributeKey; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(Long updatedTime) { this.updatedTime = updatedTime; }
}
