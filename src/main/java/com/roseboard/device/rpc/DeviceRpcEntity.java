package com.roseboard.device.rpc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "device_rpc", autoResultMap = true)
public class DeviceRpcEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private UUID deviceId;
    private Long expirationTime;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode request;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode response;
    private String status;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode additionalInfo;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public Long getExpirationTime() { return expirationTime; }
    public void setExpirationTime(Long expirationTime) { this.expirationTime = expirationTime; }
    public JsonNode getRequest() { return request; }
    public void setRequest(JsonNode request) { this.request = request; }
    public JsonNode getResponse() { return response; }
    public void setResponse(JsonNode response) { this.response = response; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public JsonNode getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(JsonNode additionalInfo) { this.additionalInfo = additionalInfo; }
}
