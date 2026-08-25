package com.roseboard.infrastructure.websocket.cluster;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceLifecycleEvent;
import com.roseboard.device.attribute.DeviceAttributeChangedEvent;
import com.roseboard.device.telemetry.DeviceTelemetrySavedEvent;
import com.roseboard.infrastructure.websocket.WebSocketService;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountUpdate;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataMapper;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityKeyType;
import com.roseboard.infrastructure.websocket.subscription.WebSocketSubscriptionRegistry;
import com.roseboard.notification.NotificationEntity;
import com.roseboard.notification.NotificationReadEvent;
import com.roseboard.notification.NotificationSavedEvent;
import com.roseboard.notification.NotificationService;
import com.roseboard.device.query.EntityQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class WebSocketPushGateway {
    private static final Logger log = LoggerFactory.getLogger(WebSocketPushGateway.class);

    private final WebSocketClusterProperties properties;
    private final StringRedisTemplate redis;
    private final WebSocketSubscriptionRegistry subscriptions;
    private final WebSocketService webSocketService;
    private final EntityQueryService entityQueryService;
    private final NotificationService notificationService;

    public WebSocketPushGateway(WebSocketClusterProperties properties,
                                  StringRedisTemplate redis,
                                  WebSocketSubscriptionRegistry subscriptions,
                                  WebSocketService webSocketService,
                                  EntityQueryService entityQueryService,
                                  NotificationService notificationService) {
        this.properties = properties;
        this.redis = redis;
        this.subscriptions = subscriptions;
        this.webSocketService = webSocketService;
        this.entityQueryService = entityQueryService;
        this.notificationService = notificationService;
    }

    public void publish(WebSocketPushEventType type, Object event) {
        WebSocketPushEnvelope envelope = new WebSocketPushEnvelope(type, JacksonUtils.toString(event));
        if (properties.isEnabled()) {
            redis.convertAndSend(properties.getPushChannel(), JacksonUtils.toString(envelope));
        } else {
            deliver(envelope);
        }
    }

    public void onRedisMessage(String body) {
        try {
            deliver(JacksonUtils.fromString(body, WebSocketPushEnvelope.class));
        } catch (Exception exception) {
            log.debug("Failed to handle ws cluster push message: {}", exception.getMessage());
        }
    }

    @EventListener
    public void onTelemetrySaved(DeviceTelemetrySavedEvent event) {
        publish(WebSocketPushEventType.TELEMETRY_SAVED, event);
    }

    @EventListener
    public void onAttributeChanged(DeviceAttributeChangedEvent event) {
        publish(WebSocketPushEventType.ATTRIBUTE_CHANGED, event);
    }

    @EventListener
    public void onDeviceLifecycle(DeviceLifecycleEvent event) {
        publish(WebSocketPushEventType.DEVICE_LIFECYCLE, event);
    }

    @EventListener
    public void onNotificationSaved(NotificationSavedEvent event) {
        publish(WebSocketPushEventType.NOTIFICATION_SAVED, event);
    }

    @EventListener
    public void onNotificationRead(NotificationReadEvent event) {
        publish(WebSocketPushEventType.NOTIFICATION_READ, event);
    }

    public void deliver(WebSocketPushEnvelope envelope) {
        if (envelope == null || envelope.type() == null || envelope.payload() == null) {
            return;
        }
        switch (envelope.type()) {
            case TELEMETRY_SAVED -> deliverTelemetrySaved(
                    JacksonUtils.fromString(envelope.payload(), DeviceTelemetrySavedEvent.class));
            case ATTRIBUTE_CHANGED -> deliverAttributeChanged(
                    JacksonUtils.fromString(envelope.payload(), DeviceAttributeChangedEvent.class));
            case DEVICE_LIFECYCLE -> deliverDeviceLifecycle(
                    JacksonUtils.fromString(envelope.payload(), DeviceLifecycleEvent.class));
            case NOTIFICATION_SAVED -> deliverNotificationSaved(
                    JacksonUtils.fromString(envelope.payload(), NotificationSavedEvent.class));
            case NOTIFICATION_READ -> deliverNotificationRead(
                    JacksonUtils.fromString(envelope.payload(), NotificationReadEvent.class));
        }
    }

    private void deliverTelemetrySaved(DeviceTelemetrySavedEvent event) {
        UUID deviceId = event.deviceId();
        String key = event.key();
        long ts = event.timestampMs();
        Object value = event.value();
        subscriptions.matchTelemetry(deviceId, key, ts, value)
                .forEach(m -> webSocketService.sendUpdateBySessionId(m.sessionId(), m.update()));
        subscriptions.matchEntityData(deviceId, EntityKeyType.TIME_SERIES, key, ts, value)
                .forEach(m -> webSocketService.sendCmdUpdateBySessionId(m.sessionId(), m.update()));
    }

    private void deliverAttributeChanged(DeviceAttributeChangedEvent event) {
        UUID deviceId = event.deviceId();
        String key = event.key();
        long ts = event.updatedTimeMs();
        Object value = event.value();
        subscriptions.matchAttributes(deviceId, event.scope(), key, ts, value)
                .forEach(m -> webSocketService.sendUpdateBySessionId(m.sessionId(), m.update()));
        subscriptions.matchEntityData(deviceId, EntityDataMapper.toEntityKeyType(event.scope()), key, ts, value)
                .forEach(m -> webSocketService.sendCmdUpdateBySessionId(m.sessionId(), m.update()));
    }

    private void deliverDeviceLifecycle(DeviceLifecycleEvent event) {
        for (var match : subscriptions.allEntityCountSubscriptions()) {
            var sub = match.subscription();
            if (!matchesEntityCount(event, sub)) {
                continue;
            }
            long count = entityQueryService.countDevices(sub.tenantId(), sub.customerId(), sub.filter());
            webSocketService.sendCmdUpdateBySessionId(match.sessionId(), new EntityCountUpdate(sub.cmdId(), (int) count));
        }
    }

    private void deliverNotificationSaved(NotificationSavedEvent event) {
        UUID recipientId = event.recipientId();
        NotificationEntity notification = event.notification();
        long totalUnread = notificationService.countUnread(recipientId);
        for (var match : subscriptions.matchNotificationsRecipient(recipientId)) {
            var sub = match.subscription();
            if (!matchesNotificationType(sub, notification.getType())) {
                continue;
            }
            sendNotificationsUpdate(match.sessionId(), sub.cmdId(), null, notification, totalUnread);
        }
        deliverNotificationsCountUpdate(recipientId, totalUnread);
    }

    private void deliverNotificationRead(NotificationReadEvent event) {
        UUID recipientId = event.recipientId();
        long totalUnread = notificationService.countUnread(recipientId);
        List<NotificationEntity> cleared = event.allNotifications() ? List.of() : null;
        for (var match : subscriptions.matchNotificationsRecipient(recipientId)) {
            sendNotificationsUpdate(match.sessionId(), match.subscription().cmdId(), cleared, null, totalUnread);
        }
        deliverNotificationsCountUpdate(recipientId, totalUnread);
    }

    private void sendNotificationsUpdate(String sessionId, int cmdId, List<NotificationEntity> notifications,
                                         NotificationEntity update, long totalUnread) {
        int sequence = subscriptions.nextNotificationSequence(sessionId, cmdId);
        webSocketService.sendCmdUpdateBySessionId(sessionId,
                new NotificationsUpdate(cmdId, notifications, update, (int) totalUnread, sequence));
    }

    private void deliverNotificationsCountUpdate(UUID recipientId, long totalUnread) {
        for (var match : subscriptions.matchNotificationsCountRecipient(recipientId)) {
            int cmdId = match.subscription().cmdId();
            int sequence = subscriptions.nextNotificationSequence(match.sessionId(), cmdId);
            webSocketService.sendCmdUpdateBySessionId(match.sessionId(),
                    new NotificationsCountUpdate(cmdId, (int) totalUnread, sequence));
        }
    }

    private boolean matchesEntityCount(DeviceLifecycleEvent event,
                                       WebSocketSubscriptionRegistry.EntityCountSubscription sub) {
        if (!event.tenantId().equals(sub.tenantId())) {
            return false;
        }
        if (sub.customerId() != null && !sub.customerId().equals(event.customerId())) {
            return false;
        }
        return entityQueryService.deviceMatchesFilter(sub.filter(), event.deviceType(), event.deviceName());
    }

    private static boolean matchesNotificationType(WebSocketSubscriptionRegistry.NotificationsSubscription sub,
                                                   String type) {
        return sub.types() == null || sub.types().isEmpty() || sub.types().contains(type);
    }
}
