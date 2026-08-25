package com.roseboard.device.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "device_profile", autoResultMap = true)
public class DeviceProfileEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private String name;
    private String description;
    private Boolean isDefault;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode additionalInfo;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode profileData;
    @Version
    private Long version;
    private UUID firmwareId;
    private UUID softwareId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public JsonNode getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(JsonNode additionalInfo) { this.additionalInfo = additionalInfo; }
    public JsonNode getProfileData() { return profileData; }
    public void setProfileData(JsonNode profileData) { this.profileData = profileData; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public UUID getFirmwareId() { return firmwareId; }
    public void setFirmwareId(UUID firmwareId) { this.firmwareId = firmwareId; }
    public UUID getSoftwareId() { return softwareId; }
    public void setSoftwareId(UUID softwareId) { this.softwareId = softwareId; }
}
