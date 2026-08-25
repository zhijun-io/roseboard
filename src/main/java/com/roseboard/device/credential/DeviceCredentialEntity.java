package com.roseboard.device.credential;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.util.UUID;

/**
 * ThingsBoard-aligned device credentials row: one credential per device.
 * Lookup uses {@code credentialsId}; type-specific payload in {@code credentialsValue}.
 */
@TableName("device_credentials")
public class DeviceCredentialEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID deviceId;
    private String credentialsType;
    private String credentialsId;
    private String credentialsValue;
    private Boolean enabled;
    private Long lastUpdatedTime;
    @Version
    private Long version;
    @TableField(exist = false)
    private String credentialsValuePlaintext;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getCredentialsType() { return credentialsType; }
    public void setCredentialsType(String credentialsType) { this.credentialsType = credentialsType; }
    public String getCredentialsId() { return credentialsId; }
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }
    public String getCredentialsValue() { return credentialsValue; }
    public void setCredentialsValue(String credentialsValue) { this.credentialsValue = credentialsValue; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(Long lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getCredentialsValuePlaintext() { return credentialsValuePlaintext; }
    public void setCredentialsValuePlaintext(String credentialsValuePlaintext) {
        this.credentialsValuePlaintext = credentialsValuePlaintext;
    }
}
