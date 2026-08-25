package com.roseboard.infrastructure.transport;

import java.util.List;

public record AttributeUpdateNotificationMsg(
        List<KeyValueEntry> sharedUpdated,
        List<String> sharedDeleted) {
}
