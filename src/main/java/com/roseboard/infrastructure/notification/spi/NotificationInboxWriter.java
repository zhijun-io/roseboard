package com.roseboard.infrastructure.notification.spi;

import java.util.UUID;

public interface NotificationInboxWriter {
    void save(UUID scopeId, UUID recipientId, String type, String subject, String body);
}
