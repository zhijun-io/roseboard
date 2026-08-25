package com.roseboard.infrastructure.websocket.cluster;

public enum WebSocketPushEventType {
    TELEMETRY_SAVED,
    ATTRIBUTE_CHANGED,
    DEVICE_LIFECYCLE,
    NOTIFICATION_SAVED,
    NOTIFICATION_READ
}
