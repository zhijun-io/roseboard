package com.roseboard.infrastructure.websocket;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public enum WebSocketSessionType {
    GENERAL,
    TELEMETRY("telemetry"),
    NOTIFICATIONS("notifications");

    private final String pluginName;

    WebSocketSessionType() {
        this.pluginName = null;
    }

    WebSocketSessionType(String pluginName) {
        this.pluginName = pluginName;
    }

    public static Optional<WebSocketSessionType> forPluginName(String name) {
        return Arrays.stream(values())
                .filter(type -> Objects.equals(type.pluginName, name))
                .findFirst();
    }
}
