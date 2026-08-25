package com.roseboard.infrastructure.transport;

import com.roseboard.device.credential.DeviceCredentialService.DevicePrincipal;

import java.util.UUID;

public record SessionInfo(
        UUID sessionId,
        UUID deviceId,
        UUID tenantId,
        UUID customerId,
        UUID deviceProfileId,
        String deviceName,
        String deviceType,
        boolean gateway,
        String nodeId,
        long createdAt,
        long lastActivityAt) {

    public static SessionInfo create(ValidateDeviceCredentialsResponse msg, String nodeId, UUID sessionId, long now) {
        ValidateDeviceCredentialsResponse.DeviceInfo info = msg.deviceInfo();
        return new SessionInfo(
                sessionId,
                info.deviceId(),
                info.tenantId(),
                info.customerId(),
                info.deviceProfileId(),
                info.deviceName(),
                info.deviceType(),
                info.gateway(),
                nodeId,
                now,
                now);
    }

    public static SessionInfo fromPrincipal(DevicePrincipal principal, long now) {
        return new SessionInfo(
                UUID.randomUUID(),
                principal.deviceId(),
                principal.tenantId(),
                null,
                null,
                null,
                null,
                false,
                null,
                now,
                now);
    }

    public static SessionInfo fromPrincipal(DevicePrincipal principal) {
        return fromPrincipal(principal, System.currentTimeMillis());
    }
}
