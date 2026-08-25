package com.roseboard.notification.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.TemplateDefinition;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateSource;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class DbNotificationTemplateSource implements NotificationTemplateSource {
    private final NotificationTemplateMapper mapper;

    public DbNotificationTemplateSource(NotificationTemplateMapper mapper) {
        this.mapper = mapper;
    }

    public static boolean isSystemKey(String templateKey) {
        return templateKey != null && templateKey.startsWith("auth.");
    }

    @Override
    public Optional<TemplateDefinition> find(UUID tenantId, String templateKey) {
        UUID lookupTenantId = isSystemKey(templateKey) ? null : tenantId;
        if (lookupTenantId != null) {
            NotificationTemplateEntity tenantRow = mapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                    .eq(NotificationTemplateEntity::getTenantId, lookupTenantId)
                    .eq(NotificationTemplateEntity::getTemplateKey, templateKey));
            if (tenantRow != null) {
                return Optional.of(toDefinition(tenantRow));
            }
        }
        NotificationTemplateEntity systemRow = mapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, templateKey));
        return systemRow == null ? Optional.empty() : Optional.of(toDefinition(systemRow));
    }

    private static TemplateDefinition toDefinition(NotificationTemplateEntity entity) {
        return new TemplateDefinition(
                entity.getId(),
                entity.getTenantId(),
                entity.getTemplateKey(),
                entity.getRenderEngine(),
                entity.getNotificationType(),
                entity.getDeliveryMethods());
    }
}
