package com.roseboard.device;

import java.util.UUID;

public record DeviceLifecycleEvent(
        UUID tenantId,
        UUID customerId,
        String deviceType,
        String deviceName,
        Action action) {

    public enum Action {
        CREATED,
        DELETED
    }
}
