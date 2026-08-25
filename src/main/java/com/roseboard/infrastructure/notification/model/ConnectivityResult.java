package com.roseboard.infrastructure.notification.model;

public record ConnectivityResult(
        ChannelKind kind,
        ConfigScope effectiveScope,
        ConnectivityStatus status,
        String message
) {
}
