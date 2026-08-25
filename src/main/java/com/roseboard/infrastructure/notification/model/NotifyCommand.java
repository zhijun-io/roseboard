package com.roseboard.infrastructure.notification.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record NotifyCommand(
        Set<ChannelKind> channels,
        List<RecipientRef> recipients,
        String type,
        String templateKey,
        Map<String, Object> vars,
        NotifyOptions options
) {
    public NotifyCommand {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels is required");
        }
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients is required");
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey is required");
        }
        if (options == null) {
            options = NotifyOptions.defaults();
        }
        if (vars == null) {
            vars = Map.of();
        }
    }
}
