package com.roseboard.notification;

import java.util.UUID;

public record NotificationReadEvent(UUID recipientId, UUID notificationId, boolean allNotifications) {
}
