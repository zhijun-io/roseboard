package com.roseboard.infrastructure.transport;

public record SubscribeToAttributeUpdatesMsg(boolean unsubscribe) {
    public static SubscribeToAttributeUpdatesMsg subscribe() {
        return new SubscribeToAttributeUpdatesMsg(false);
    }
}
