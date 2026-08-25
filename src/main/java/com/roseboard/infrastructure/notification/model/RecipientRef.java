package com.roseboard.infrastructure.notification.model;

import java.util.UUID;

public record RecipientRef(UUID userId, String email, String phone) {
}
