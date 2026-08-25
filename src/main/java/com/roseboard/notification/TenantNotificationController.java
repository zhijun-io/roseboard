package com.roseboard.notification;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.model.NotifyResult;
import com.roseboard.notification.catalog.NotificationTargetService;
import com.roseboard.notification.catalog.NotificationTargetEntity;
import com.roseboard.notification.channel.ChannelConfigService;
import com.roseboard.notification.channel.ChannelConfigService.ChannelUpdate;
import com.roseboard.notification.channel.ChannelConfigService.ChannelView;
import com.roseboard.notification.template.NotificationTemplateEntity;
import com.roseboard.notification.template.TemplateService;
import com.roseboard.common.PageData;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * 租户通知接口：管理租户目标、渠道、模板和通知投递。
 */
@RestController
@RequestMapping("/api/notifications")
public class TenantNotificationController {
    private final ChannelConfigService channelConfigService;
    private final NotificationCenter notificationCenter;
    private final TemplateService templateService;
    private final NotificationTargetService notificationTargetService;
    private final DataScopeAuthorizer dataScopeService;

    public TenantNotificationController(ChannelConfigService channelConfigService,
                                        NotificationCenter notificationCenter,
                                        TemplateService templateService,
                                        NotificationTargetService notificationTargetService,
                                        DataScopeAuthorizer dataScopeService) {
        this.channelConfigService = channelConfigService;
        this.notificationCenter = notificationCenter;
        this.templateService = templateService;
        this.notificationTargetService = notificationTargetService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 查询通知目标收件人。
     */
    @PostMapping("/recipient-queries")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public PageData<RecipientRef> targetRecipients(@RequestBody JsonNode payload,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(defaultValue = "0") long page,
                                                    Authentication authentication) {
        return notificationTargetService.recipients(dataScopeService.requireTenantId(authentication),
                payload, pageSize, page);
    }

    /**
     * 查询可用通知投递方式。
     */
    @GetMapping("/delivery-methods")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public List<ChannelKind> availableDeliveryMethods(Authentication authentication) {
        return notificationCenter.availableChannels(dataScopeService.requireTenantId(authentication));
    }


    /**
     * 按 ID 批量查询通知目标。
     */
    @GetMapping(value = "/targets", params = "ids")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public List<NotificationTargetEntity> getTargetsByIds(@RequestParam UUID[] ids,
                                                           Authentication authentication) {
        return notificationTargetService.listTargetsByIds(dataScopeService.requireTenantId(authentication), ids);
    }

    /**
     * 查询通知渠道配置。
     */
    @GetMapping("/channels/{kind}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public ChannelView getChannel(@PathVariable String kind, Authentication authentication) {
        return channelConfigService.getTenantChannel(dataScopeService.requireTenantId(authentication),
                NotificationHttp.parseChannelKind(kind));
    }

    /**
     * 创建或更新通知渠道配置。
     */
    @PutMapping("/channels/{kind}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_UPDATED, entityType = EntityType.NOTIFICATION,
            entityName = "'channel-' + #kind")
    public ChannelView saveChannel(@PathVariable String kind,
                                   @RequestBody JsonNode payload,
                                   Authentication authentication) {
        UUID tenantId = dataScopeService.requireTenantId(authentication);
        try {
            return channelConfigService.saveTenantChannel(tenantId, NotificationHttp.parseChannelKind(kind),
                    ChannelUpdate.fromJson(payload, true));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    /**
     * 验证通知渠道连通性。
     */
    @PostMapping("/channels/{kind}/verifications")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_UPDATED, entityType = EntityType.NOTIFICATION,
            entityName = "'channel-verify-' + #kind")
    public ConnectivityResult verifyChannel(@PathVariable String kind, Authentication authentication) {
        return notificationCenter.verifyConnectivity(
                dataScopeService.requireTenantId(authentication), NotificationHttp.parseChannelKind(kind));
    }

    /**
     * 查询通知模板。
     */
    @GetMapping("/templates/{templateKey}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public NotificationTemplateEntity getTemplate(@PathVariable String templateKey, Authentication authentication) {
        return templateService.getTenant(dataScopeService.requireTenantId(authentication), templateKey);
    }

    /**
     * 创建或更新通知模板。
     */
    @PutMapping("/templates/{templateKey}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_UPDATED, entityType = EntityType.NOTIFICATION,
            entityName = "'template-' + #templateKey")
    public NotificationTemplateEntity saveTemplate(@PathVariable String templateKey,
                                                   @RequestBody JsonNode payload,
                                                   Authentication authentication) {
        return templateService.saveTenant(dataScopeService.requireTenantId(authentication), templateKey, payload);
    }

    /**
     * 处理 /targets 对应的接口请求。
     */
    @GetMapping("/targets")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public List<NotificationTargetEntity> listTargets(Authentication authentication) {
        return notificationTargetService.listTargets(dataScopeService.requireTenantId(authentication));
    }

    /**
     * 处理 /targets 对应的接口请求。
     */
    @GetMapping(value = "/targets", params = "pageSize")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public PageData<NotificationTargetEntity> pageTargets(
            @RequestParam long pageSize,
            @RequestParam(defaultValue = "0") long page,
            @RequestParam(required = false) String textSearch,
            Authentication authentication) {
        return notificationTargetService.pageTargets(dataScopeService.requireTenantId(authentication),
                pageSize, page, textSearch);
    }

    /**
     * 查询通知目标。
     */
    @GetMapping("/targets/{targetId}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public NotificationTargetEntity getTarget(@PathVariable UUID targetId, Authentication authentication) {
        return notificationTargetService.getTarget(dataScopeService.requireTenantId(authentication), targetId);
    }

    /**
     * 创建通知目标。
     */
    @PostMapping("/targets")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_UPDATED, entityType = EntityType.NOTIFICATION, entityId = "#result.id")
    public NotificationTargetEntity createTarget(@RequestBody JsonNode payload, Authentication authentication) {
        return notificationTargetService.saveTarget(dataScopeService.requireTenantId(authentication), null, payload);
    }
    /**
     * 更新通知目标。
     */
    @PutMapping("/targets/{targetId}")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_UPDATED, entityType = EntityType.NOTIFICATION, entityId = "#targetId")
    public NotificationTargetEntity updateTarget(@PathVariable UUID targetId,
                                                 @RequestBody JsonNode payload,
                                                 Authentication authentication) {
        return notificationTargetService.saveTarget(dataScopeService.requireTenantId(authentication), targetId, payload);
    }

    /**
     * 删除通知目标。
     */
    @DeleteMapping("/targets/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_DELETED, entityType = EntityType.NOTIFICATION, entityId = "#targetId")
    public void deleteTarget(@PathVariable UUID targetId, Authentication authentication) {
        notificationTargetService.deleteTarget(dataScopeService.requireTenantId(authentication), targetId);
    }

    /**
     * 发送通知或验证码。
     */
    @PostMapping("/deliveries")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_SENT, entityType = EntityType.NOTIFICATION)
    public NotifyResult send(@RequestBody JsonNode payload, Authentication authentication) {
        return notificationTargetService.send(dataScopeService.requireTenantId(authentication), payload);
    }
}
