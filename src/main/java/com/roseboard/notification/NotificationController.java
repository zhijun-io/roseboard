package com.roseboard.notification;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.common.PageData;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 通知收件箱接口：分页查询通知、统计未读数和标记已读。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;
    private final DataScopeAuthorizer dataScopeService;

    public NotificationController(NotificationService notificationService, DataScopeAuthorizer dataScopeService) {
        this.notificationService = notificationService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 分页查询资源。
     */
    @GetMapping
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public PageData<NotificationEntity> page(@RequestParam(defaultValue = "10") long pageSize,
                                             @RequestParam(defaultValue = "0") long page,
                                             @RequestParam(required = false) String textSearch,
                                             @RequestParam(required = false) String deliveryMethod,
                                             @RequestParam(defaultValue = "false") boolean unreadOnly,
                                             Authentication authentication) {
        UUID recipientId = dataScopeService.requireUserId(authentication);
        return notificationService.page(recipientId, pageSize, page, textSearch, deliveryMethod, unreadOnly);
    }

    /**
     * 查询未读通知数量。
     */
    @GetMapping("/unread-count")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    public long unreadCount(@RequestParam(defaultValue = "WEB") String deliveryMethod,
                            Authentication authentication) {
        return notificationService.countUnread(dataScopeService.requireUserId(authentication), deliveryMethod);
    }

    /**
     * 将当前用户全部通知标记为已读。
     */
    @PutMapping("/read")
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    @Audited(action = AuditActions.NOTIFICATION_READ, entityType = EntityType.NOTIFICATION)
    public void markAllReadTb(@RequestParam(defaultValue = "WEB") String deliveryMethod,
                              Authentication authentication) {
        notificationService.markAllRead(dataScopeService.requireUserId(authentication), deliveryMethod);
    }

    /**
     * 标记通知为已读。
     */
    @PutMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.READ)
    @Audited(action = AuditActions.NOTIFICATION_READ, entityType = EntityType.NOTIFICATION, entityId = "#notificationId")
    public void markRead(@PathVariable UUID notificationId, Authentication authentication) {
        notificationService.markRead(dataScopeService.requireUserId(authentication), notificationId);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(resource = EntityType.NOTIFICATION, operation = Operation.WRITE)
    @Audited(action = AuditActions.NOTIFICATION_DELETED, entityType = EntityType.NOTIFICATION, entityId = "#notificationId")
    public void delete(@PathVariable UUID notificationId, Authentication authentication) {
        notificationService.delete(dataScopeService.requireUserId(authentication), notificationId);
    }

}
