package com.roseboard.infrastructure.notification.model;

public enum ConnectionMode {
    INHERIT,
    CUSTOM;

    public static ConnectionMode parse(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown connectionMode: " + raw);
        }
    }
}
