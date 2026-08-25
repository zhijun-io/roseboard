package com.roseboard.infrastructure.websocket.cluster;

public record WebSocketPushEnvelope(WebSocketPushEventType type, String payload) {
}
