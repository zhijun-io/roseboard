package com.roseboard.infrastructure.notification.model;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record TemplateDefinition(
        UUID id,
        UUID tenantId,
        String templateKey,
        String renderEngine,
        String notificationType,
        JsonNode deliveryMethods
) {
}
