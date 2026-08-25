package com.roseboard.infrastructure.websocket.cmd;

public enum WsCmdType {
    AUTH,
    ATTRIBUTES,
    TIMESERIES,
    TIMESERIES_HISTORY,
    ENTITY_DATA,
    ENTITY_DATA_UNSUBSCRIBE,
    ENTITY_COUNT,
    ENTITY_COUNT_UNSUBSCRIBE,
    NOTIFICATIONS,
    NOTIFICATIONS_COUNT,
    MARK_NOTIFICATIONS_AS_READ,
    MARK_ALL_NOTIFICATIONS_AS_READ,
    NOTIFICATIONS_UNSUBSCRIBE;

    public boolean usesCmdUpdate() {
        return switch (this) {
            case ENTITY_DATA, ENTITY_DATA_UNSUBSCRIBE, ENTITY_COUNT, ENTITY_COUNT_UNSUBSCRIBE,
                 NOTIFICATIONS, NOTIFICATIONS_COUNT, NOTIFICATIONS_UNSUBSCRIBE -> true;
            default -> false;
        };
    }
}
