package com.roseboard.infrastructure.notification.spi;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;

import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelConfigResolver {
    Optional<ResolvedChannelConfig> resolve(UUID tenantId, ChannelKind kind);
}
