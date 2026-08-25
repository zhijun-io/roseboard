package com.roseboard.infrastructure.transport;

import java.util.UUID;

public record ValidateDeviceCredentialsResponse(DeviceInfo deviceInfo, String credentials) {

    public record DeviceInfo(
            UUID deviceId,
            UUID tenantId,
            UUID customerId,
            UUID deviceProfileId,
            String deviceName,
            String deviceType,
            boolean gateway) {
    }

    public boolean hasDeviceInfo() {
        return deviceInfo != null;
    }
}
