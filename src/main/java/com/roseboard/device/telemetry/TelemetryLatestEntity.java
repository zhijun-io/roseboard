package com.roseboard.device.telemetry;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "telemetry_latest", autoResultMap = true)
public class TelemetryLatestEntity {
    private UUID tenantId;
    private UUID deviceId;
    private String telemetryKey;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class)
    private JsonNode pointValue;
    private Long timestampMs;
    private UUID writeId;
    private Long updatedTime;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getTelemetryKey() { return telemetryKey; }
    public void setTelemetryKey(String telemetryKey) { this.telemetryKey = telemetryKey; }
    public JsonNode getPointValue() { return pointValue; }
    public void setPointValue(JsonNode pointValue) { this.pointValue = pointValue; }
    public Long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(Long timestampMs) { this.timestampMs = timestampMs; }
    public UUID getWriteId() { return writeId; }
    public void setWriteId(UUID writeId) { this.writeId = writeId; }
    public Long getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(Long updatedTime) { this.updatedTime = updatedTime; }
}
