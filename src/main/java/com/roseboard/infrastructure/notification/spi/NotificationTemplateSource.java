package com.roseboard.infrastructure.notification.spi;

import com.roseboard.infrastructure.notification.model.TemplateDefinition;

import com.roseboard.infrastructure.notification.model.TemplateDefinition;

import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateSource {
    Optional<TemplateDefinition> find(UUID tenantId, String templateKey);
}
