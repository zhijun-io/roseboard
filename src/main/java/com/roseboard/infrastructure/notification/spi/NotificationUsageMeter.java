package com.roseboard.infrastructure.notification.spi;

import com.roseboard.infrastructure.notification.model.ChannelKind;

import java.util.UUID;

/** Optional host application hook for notification usage accounting. */
public interface NotificationUsageMeter {
    void record(UUID tenantId, ChannelKind channel);
}
