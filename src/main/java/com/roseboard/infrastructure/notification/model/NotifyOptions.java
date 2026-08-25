package com.roseboard.infrastructure.notification.model;

public record NotifyOptions(boolean ignoreTenantChannelDisabled, boolean countUsage) {
    public static NotifyOptions systemNotification() {
        return new NotifyOptions(true, false);
    }

    public static NotifyOptions defaults() {
        return new NotifyOptions(false, true);
    }
}
