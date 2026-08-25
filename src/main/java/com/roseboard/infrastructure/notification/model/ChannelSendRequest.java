package com.roseboard.infrastructure.notification.model;

import java.util.List;
import java.util.UUID;

public record ChannelSendRequest(
        UUID tenantId,
        String notificationType,
        List<RecipientRef> recipients,
        RenderedChannelContent rendered,
        ResolvedChannelConfig channelConfig,
        boolean countUsage
) {
}
