package com.roseboard.notification.template;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * 平台通知配置接口：管理平台渠道、短信配置和系统模板。
 */
@RestController
@RequestMapping("/api/notifications/templates")
public class NotificationTemplateController {
    private final TemplateService templateService;

    public NotificationTemplateController(
                                          TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 查询系统通知模板。
     */
    @GetMapping("/system/{templateKey}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public NotificationTemplateEntity getSystemTemplate(@PathVariable String templateKey) {
        return templateService.getSystem(templateKey);
    }

    /**
     * 保存系统模板。
     */
    @PutMapping("/system/{templateKey}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "'notification-template-' + #templateKey")
    public NotificationTemplateEntity saveSystemTemplate(@PathVariable String templateKey,
                                                           @RequestBody JsonNode payload) {
        return templateService.saveSystem(templateKey, payload);
    }
}
