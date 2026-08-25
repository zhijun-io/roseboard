package com.roseboard.device.connectivity;

public record DeviceConnectivityState(
        boolean active,
        long lastConnectTime,
        long lastDisconnectTime,
        long lastActivityTime) {
}
