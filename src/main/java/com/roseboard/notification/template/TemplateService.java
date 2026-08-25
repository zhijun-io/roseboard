package com.roseboard.notification.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Service
public class TemplateService {
    private final NotificationTemplateMapper mapper;

    public TemplateService(NotificationTemplateMapper mapper) {
        this.mapper = mapper;
    }

    public static boolean isSystemKey(String templateKey) {
        return DbNotificationTemplateSource.isSystemKey(templateKey);
    }

    public NotificationTemplateEntity getTenant(UUID tenantId, String templateKey) {
        rejectSystemKey(templateKey);
        NotificationTemplateEntity entity = findTenantRow(tenantId, templateKey);
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + templateKey);
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationTemplateEntity saveTenant(UUID tenantId, String templateKey, JsonNode payload) {
        rejectSystemKey(templateKey);
        String renderEngine = requiredText(payload, "renderEngine");
        if (!"SIMPLE".equalsIgnoreCase(renderEngine)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tenant templates only support SIMPLE render engine");
        }
        long now = System.currentTimeMillis();
        NotificationTemplateEntity existing = findTenantRow(tenantId, templateKey);
        NotificationTemplateEntity entity = existing == null ? new NotificationTemplateEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID());
            entity.setTenantId(tenantId);
            entity.setTemplateKey(templateKey);
            entity.setCreatedTime(now);
        }
        entity.setRenderEngine("SIMPLE");
        entity.setNotificationType(requiredText(payload, "notificationType"));
        entity.setName(requiredText(payload, "name"));
        entity.setDeliveryMethods(requiredNode(payload, "deliveryMethods"));
        entity.setUpdatedTime(now);
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return entity;
    }

    public NotificationTemplateEntity getSystem(String templateKey) {
        NotificationTemplateEntity entity = mapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, templateKey));
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "System template not found: " + templateKey);
        }
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationTemplateEntity saveSystem(String templateKey, JsonNode payload) {
        long now = System.currentTimeMillis();
        NotificationTemplateEntity entity = mapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .isNull(NotificationTemplateEntity::getTenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, templateKey));
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "System template not found: " + templateKey);
        }
        if (payload.has("renderEngine")) {
            entity.setRenderEngine(requiredText(payload, "renderEngine"));
        }
        if (payload.has("notificationType")) {
            entity.setNotificationType(requiredText(payload, "notificationType"));
        }
        if (payload.has("name")) {
            entity.setName(requiredText(payload, "name"));
        }
        if (payload.has("deliveryMethods")) {
            entity.setDeliveryMethods(requiredNode(payload, "deliveryMethods"));
        }
        entity.setUpdatedTime(now);
        mapper.updateById(entity);
        return entity;
    }

    private NotificationTemplateEntity findTenantRow(UUID tenantId, String templateKey) {
        return mapper.selectOne(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .eq(NotificationTemplateEntity::getTenantId, tenantId)
                .eq(NotificationTemplateEntity::getTemplateKey, templateKey));
    }

    private static void rejectSystemKey(String templateKey) {
        if (isSystemKey(templateKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System template keys cannot be overridden by tenant");
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = payload.path(field).asText("");
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private static JsonNode requiredNode(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || !value.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }
}
