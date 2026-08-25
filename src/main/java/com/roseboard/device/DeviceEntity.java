package com.roseboard.device;

import com.baomidou.mybatisplus.annotation.*;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "device", autoResultMap = true)
public class DeviceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private UUID customerId;
    private UUID deviceProfileId;
    private String name;
    private String type;
    private String label;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode additionalInfo;
    @Version
    private Long version;
    private UUID firmwareId;
    private UUID softwareId;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode deviceData;
    private UUID externalId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getDeviceProfileId() { return deviceProfileId; }
    public void setDeviceProfileId(UUID deviceProfileId) { this.deviceProfileId = deviceProfileId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public JsonNode getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(JsonNode additionalInfo) { this.additionalInfo = additionalInfo; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public UUID getFirmwareId() { return firmwareId; }
    public void setFirmwareId(UUID firmwareId) { this.firmwareId = firmwareId; }
    public UUID getSoftwareId() { return softwareId; }
    public void setSoftwareId(UUID softwareId) { this.softwareId = softwareId; }
    public JsonNode getDeviceData() { return deviceData; }
    public void setDeviceData(JsonNode deviceData) { this.deviceData = deviceData; }
    public UUID getExternalId() { return externalId; }
    public void setExternalId(UUID externalId) { this.externalId = externalId; }
}
