package com.roseboard.infrastructure.transport;

import java.util.UUID;

public record SessionCloseNotification(UUID sessionId, String message) {

    public static SessionCloseNotification timeout(UUID sessionId) {
        return new SessionCloseNotification(sessionId, "session timeout!");
    }
}
