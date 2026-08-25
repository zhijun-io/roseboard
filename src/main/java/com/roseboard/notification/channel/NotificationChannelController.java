package com.roseboard.notification.channel;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.notification.NotificationHttp;
import com.roseboard.notification.channel.ChannelConfigService.ChannelUpdate;
import com.roseboard.notification.channel.ChannelConfigService.ChannelView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 平台通知配置接口：管理平台渠道、短信配置和系统模板。
 */
@RestController
@RequestMapping("/api/notifications/platform")
public class NotificationChannelController {
    private final ChannelConfigService channelConfigService;
    private final NotificationCenter notificationCenter;

    public NotificationChannelController(ChannelConfigService channelConfigService,
                                         NotificationCenter notificationCenter) {
        this.channelConfigService = channelConfigService;
        this.notificationCenter = notificationCenter;
    }

    /**
     * 查询通知渠道配置。
     */
    @GetMapping("/channels/{kind}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public ChannelView getChannel(@PathVariable String kind) {
        return channelConfigService.getPlatform(NotificationHttp.parseChannelKind(kind));
    }

    /**
     * 创建或更新通知渠道配置。
     */
    @PutMapping("/channels/{kind}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "'notification-channel-' + #kind")
    public ChannelView saveChannel(@PathVariable String kind, @RequestBody JsonNode payload) {
        try {
            return channelConfigService.savePlatform(NotificationHttp.parseChannelKind(kind),
                    ChannelUpdate.fromJson(payload, false));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    /**
     * 验证通知渠道连通性。
     */
    @PostMapping("/channels/{kind}/verify")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "'notification-channel-verify-' + #kind")
    public ConnectivityResult verifyChannel(@PathVariable String kind) {
        return notificationCenter.verifyConnectivity(null, NotificationHttp.parseChannelKind(kind));
    }
    /**
     * 查询平台邮件配置状态。
     */
    @GetMapping("/channels/mail/status")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public Map<String, Boolean> mailStatus() {
        return Map.of("configured", channelConfigService.platformEmailConfigured());
    }

    /**
     * 查询平台短信配置。
     */
    @GetMapping("/sms-settings")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public JsonNode getSmsSettings() {
        return channelConfigService.smsSettingsPublic();
    }

    /**
     * 保存平台短信配置。
     */
    @PutMapping("/sms-settings")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "sms")
    public JsonNode saveSmsSettings(@RequestBody JsonNode value) {
        try {
            return channelConfigService.saveSmsSettings(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
