package com.roseboard.infrastructure.notification.model;

public enum ChannelKind {
    WEB,
    EMAIL,
    SMS;

    public static ChannelKind parse(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown channel kind: " + raw);
        }
    }
}
