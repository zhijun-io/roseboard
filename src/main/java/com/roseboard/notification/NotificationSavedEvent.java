package com.roseboard.notification;

import java.util.UUID;

public record NotificationSavedEvent(UUID recipientId, NotificationEntity notification) {
}
