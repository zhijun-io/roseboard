package com.roseboard.infrastructure.websocket;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceService;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttribute;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.telemetry.TelemetryLatest;
import com.roseboard.device.telemetry.TelemetryOrder;
import com.roseboard.device.telemetry.TelemetryPoint;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.infrastructure.security.jwt.JwtAuthenticationToken;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;
import com.roseboard.infrastructure.websocket.cmd.WsCommandsWrapper;
import com.roseboard.infrastructure.websocket.cmd.v1.AttributesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.GetHistoryCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.SubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.TimeseriesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityData;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataMapper;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.WsPageData;
import com.roseboard.infrastructure.websocket.sub.TelemetrySubscriptionUpdate;
import com.roseboard.infrastructure.websocket.subscription.WebSocketSubscriptionRegistry;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkAllNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountUpdate;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsUpdate;
import com.roseboard.notification.NotificationEntity;
import com.roseboard.notification.NotificationService;
import com.roseboard.device.query.EntityDataKeys;
import com.roseboard.device.query.EntityDataSnapshot;
import com.roseboard.device.query.EntityQueryService;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import com.roseboard.device.query.filter.SingleEntityFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class DefaultWebSocketService implements WebSocketService {
    public static final int NUMBER_OF_PING_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(DefaultWebSocketService.class);

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;
    private final DeviceAttributeService attributeService;
    private final EntityQueryService entityQueryService;
    private final NotificationService notificationService;
    private final WebSocketSubscriptionRegistry subscriptions;
    private final WebSocketLimitTracker limitTracker;
    private final WebSocketMsgEndpoint msgEndpoint;
    private final ConcurrentMap<String, WebSocketSessionRef> sessions = new ConcurrentHashMap<>();

    @Value("${server.ws.ping_timeout:30000}")
    private long pingTimeout;

    private ScheduledExecutorService pingExecutor;

    public DefaultWebSocketService(DeviceService deviceService,
                                   TelemetryService telemetryService,
                                   DeviceAttributeService attributeService,
                                   EntityQueryService entityQueryService,
                                   NotificationService notificationService,
                                   WebSocketSubscriptionRegistry subscriptions,
                                   WebSocketLimitTracker limitTracker,
                                   @Lazy WebSocketMsgEndpoint msgEndpoint) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
        this.attributeService = attributeService;
        this.entityQueryService = entityQueryService;
        this.notificationService = notificationService;
        this.subscriptions = subscriptions;
        this.limitTracker = limitTracker;
        this.msgEndpoint = msgEndpoint;
    }

    @PostConstruct
    void startPingScheduler() {
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-ping");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = Math.max(pingTimeout / NUMBER_OF_PING_ATTEMPTS, 1);
        pingExecutor.scheduleWithFixedDelay(this::sendPing, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopPingScheduler() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
    }

    @Override
    public void handleSessionOpened(WebSocketSessionRef sessionRef) {
        sessions.put(sessionRef.getSessionId(), sessionRef);
    }

    @Override
    public void handleSessionClosed(WebSocketSessionRef sessionRef) {
        sessions.remove(sessionRef.getSessionId());
        limitTracker.releaseSessionSubscriptions(sessionRef);
        subscriptions.removeSession(sessionRef.getSessionId());
    }

    @Override
    public void cleanupIfStale(String sessionId) {
        teardownIfStale(sessionId);
    }

    @Override
    public void handleCommands(WebSocketSessionRef sessionRef, WsCommandsWrapper commandsWrapper) {
        if (sessionRef.getSecurityPrincipal() == null || commandsWrapper == null || commandsWrapper.getCmds() == null) {
            return;
        }
        sessions.put(sessionRef.getSessionId(), sessionRef);
        for (WsCmd cmd : commandsWrapper.getCmds()) {
            if (cmd == null || cmd.getType() == null) {
                continue;
            }
            try {
                dispatchCommand(sessionRef, cmd);
            } catch (ResponseStatusException exception) {
                sendCommandError(sessionRef, cmd, SubscriptionErrorCode.UNAUTHORIZED, exception.getReason());
            } catch (IllegalArgumentException exception) {
                sendCommandError(sessionRef, cmd, SubscriptionErrorCode.BAD_REQUEST, exception.getMessage());
            } catch (Exception exception) {
                sendCommandError(sessionRef, cmd, SubscriptionErrorCode.INTERNAL_ERROR, exception.getMessage());
            }
        }
    }

    @Override
    public void sendUpdate(WebSocketSessionRef sessionRef, TelemetrySubscriptionUpdate update) {
        sendJson(sessionRef, update.getSubscriptionId(), update);
    }

    @Override
    public void sendUpdateBySessionId(String sessionId, TelemetrySubscriptionUpdate update) {
        withSession(sessionId, session -> sendUpdate(session, update));
    }

    @Override
    public void sendCmdUpdate(WebSocketSessionRef sessionRef, CmdUpdate update) {
        sendJson(sessionRef, update.getCmdId(), update);
    }

    @Override
    public void sendCmdUpdateBySessionId(String sessionId, CmdUpdate update) {
        withSession(sessionId, session -> sendCmdUpdate(session, update));
    }

    @Override
    public void sendError(WebSocketSessionRef sessionRef, int cmdId, SubscriptionErrorCode errorCode, String errorMsg) {
        sendUpdate(sessionRef, new TelemetrySubscriptionUpdate(cmdId, errorCode, errorMsg));
    }

    private void dispatchCommand(WebSocketSessionRef sessionRef, WsCmd cmd) {
        try {
            dispatchCommandInternal(sessionRef, cmd);
        } catch (IOException exception) {
            sendCommandError(sessionRef, cmd, SubscriptionErrorCode.INTERNAL_ERROR, exception.getMessage());
        }
    }

    private void dispatchCommandInternal(WebSocketSessionRef sessionRef, WsCmd cmd) throws IOException {
        switch (cmd.getType()) {
            case TIMESERIES -> handleTimeseries(sessionRef, (TimeseriesSubscriptionCmd) cmd);
            case ATTRIBUTES -> handleAttributes(sessionRef, (AttributesSubscriptionCmd) cmd);
            case TIMESERIES_HISTORY -> handleHistory(sessionRef, (GetHistoryCmd) cmd);
            case ENTITY_DATA -> handleEntityData(sessionRef, (EntityDataCmd) cmd);
            case ENTITY_DATA_UNSUBSCRIBE, ENTITY_COUNT_UNSUBSCRIBE -> handleV2Unsubscribe(sessionRef, cmd);
            case ENTITY_COUNT -> handleEntityCount(sessionRef, (EntityCountCmd) cmd);
            case NOTIFICATIONS -> handleNotifications(sessionRef, (NotificationsSubCmd) cmd);
            case NOTIFICATIONS_COUNT -> handleNotificationsCount(sessionRef, (NotificationsCountSubCmd) cmd);
            case MARK_NOTIFICATIONS_AS_READ -> handleMarkNotificationsAsRead(sessionRef, (MarkNotificationsAsReadCmd) cmd);
            case MARK_ALL_NOTIFICATIONS_AS_READ -> handleMarkAllNotificationsAsRead(sessionRef, (MarkAllNotificationsAsReadCmd) cmd);
            case NOTIFICATIONS_UNSUBSCRIBE -> handleNotificationsUnsubscribe(sessionRef, cmd);
            default -> sendError(sessionRef, cmd.getCmdId(), SubscriptionErrorCode.BAD_REQUEST,
                    "Unsupported command type: " + cmd.getType());
        }
    }

    private void sendCommandError(WebSocketSessionRef sessionRef, WsCmd cmd,
                                  SubscriptionErrorCode errorCode, String errorMsg) {
        if (cmd.getType().usesCmdUpdate()) {
            sendCmdUpdate(sessionRef, EntityDataMapper.errorUpdate(cmd.getType(), cmd.getCmdId(), errorCode, errorMsg));
        } else {
            sendError(sessionRef, cmd.getCmdId(), errorCode, errorMsg);
        }
    }

    private void withSession(String sessionId, Consumer<WebSocketSessionRef> action) {
        WebSocketSessionRef sessionRef = sessions.get(sessionId);
        if (sessionRef != null) {
            if (!msgEndpoint.isOpen(sessionId)) {
                teardownIfStale(sessionId);
                return;
            }
            action.accept(sessionRef);
            return;
        }
        if (!msgEndpoint.isOpen(sessionId)) {
            teardownIfStale(sessionId);
        }
    }

    private void sendPing() {
        long currentTime = System.currentTimeMillis();
        for (WebSocketSessionRef sessionRef : List.copyOf(sessions.values())) {
            String sessionId = sessionRef.getSessionId();
            if (!msgEndpoint.isOpen(sessionId)) {
                teardownIfStale(sessionId);
                continue;
            }
            try {
                msgEndpoint.sendPing(sessionRef, currentTime);
            } catch (IOException exception) {
                log.debug("[{}] Failed to send ping", sessionId, exception);
                teardownIfStale(sessionId);
            }
        }
    }

    private void sendJson(WebSocketSessionRef sessionRef, int msgId, Object payload) {
        String sessionId = sessionRef.getSessionId();
        if (!msgEndpoint.isOpen(sessionId)) {
            teardownIfStale(sessionId);
            return;
        }
        try {
            msgEndpoint.send(sessionRef, msgId, JacksonUtils.toString(payload));
        } catch (IOException exception) {
            teardownIfStale(sessionId);
        }
    }

    private void teardownIfStale(String sessionId) {
        if (msgEndpoint.isOpen(sessionId)) {
            return;
        }
        log.info("[{}] Cleaning up stale session", sessionId);
        WebSocketSessionRef sessionRef = sessions.remove(sessionId);
        if (sessionRef != null && sessionRef.getSecurityPrincipal() != null) {
            limitTracker.releaseSession(sessionRef);
            limitTracker.releaseSessionSubscriptions(sessionRef);
        }
        subscriptions.removeSession(sessionId);
    }

    private void handleTimeseries(WebSocketSessionRef sessionRef, TimeseriesSubscriptionCmd cmd) throws IOException {
        if (!limitTracker.processSubscription(sessionRef, cmd)) {
            return;
        }
        if (cmd.isUnsubscribe()) {
            subscriptions.unregisterTimeseries(sessionRef.getSessionId(), cmd.getCmdId());
            return;
        }
        DeviceEntity device = requireDevice(sessionRef, cmd);
        Set<String> keys = parseKeys(cmd.getKeys());
        subscriptions.registerTimeseries(sessionRef, cmd.getCmdId(), device.getId(), keys);
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        for (String key : keys) {
            TelemetryLatest latest = telemetryService.latest(device.getTenantId(), device.getId(), key);
            if (latest != null) {
                data.put(key, pointList(latest.timestampMs(), latest.value()));
            }
        }
        sendUpdate(sessionRef, new TelemetrySubscriptionUpdate(cmd.getCmdId(), data));
    }

    private void handleAttributes(WebSocketSessionRef sessionRef, AttributesSubscriptionCmd cmd) throws IOException {
        if (!limitTracker.processSubscription(sessionRef, cmd)) {
            return;
        }
        if (cmd.isUnsubscribe()) {
            subscriptions.unregisterAttributes(sessionRef.getSessionId(), cmd.getCmdId());
            return;
        }
        DeviceEntity device = requireDevice(sessionRef, cmd);
        AttributeScope scope = parseScope(cmd.getScope());
        Set<String> keys = parseKeys(cmd.getKeys());
        subscriptions.registerAttributes(sessionRef, cmd.getCmdId(), device.getId(), scope, keys);
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        if (keys.isEmpty()) {
            for (DeviceAttribute attribute : attributeService.findAll(device.getTenantId(), device.getId(), scope)) {
                data.put(attribute.key().value(), pointList(now, attribute.value().value()));
            }
        } else {
            for (String key : keys) {
                DeviceAttribute attribute = attributeService.find(device.getTenantId(), device.getId(), scope, new AttributeKey(key));
                if (attribute != null) {
                    data.put(key, pointList(now, attribute.value().value()));
                }
            }
        }
        sendUpdate(sessionRef, new TelemetrySubscriptionUpdate(cmd.getCmdId(), data));
    }

    private void handleEntityData(WebSocketSessionRef sessionRef, EntityDataCmd cmd) {
        if (cmd.getLatestCmd() == null) {
            throw new IllegalArgumentException("latestCmd is required");
        }
        SingleEntityFilter filter = EntityDataMapper.toSingleEntityFilter(cmd.getQuery());
        DeviceEntity device = requireDevice(sessionRef, filter.getEntityType(), filter.getEntityId());
        EntityDataKeys keys = EntityDataMapper.toEntityDataKeys(cmd.getLatestCmd().getKeys());
        subscriptions.registerEntityData(sessionRef, cmd.getCmdId(), device.getId(), keys);
        EntityDataSnapshot snapshot = entityQueryService.loadEntityData(device.getTenantId(), device.getId(), keys);
        EntityData entityData = EntityDataMapper.toEntityData(snapshot, cmd.getLatestCmd().getKeys());
        sendCmdUpdate(sessionRef, new EntityDataUpdate(cmd.getCmdId(), WsPageData.single(entityData)));
    }

    private void handleV2Unsubscribe(WebSocketSessionRef sessionRef, WsCmd cmd) {
        String sessionId = sessionRef.getSessionId();
        if (cmd.getType() == WsCmdType.ENTITY_DATA_UNSUBSCRIBE) {
            subscriptions.unregisterEntityData(sessionId, cmd.getCmdId());
        } else {
            subscriptions.unregisterEntityCount(sessionId, cmd.getCmdId());
        }
    }

    private void handleEntityCount(WebSocketSessionRef sessionRef, EntityCountCmd cmd) {
        SecurityUser user = sessionRef.getSecurityPrincipal();
        DeviceTypeFilter filter = EntityDataMapper.toDeviceTypeFilter(cmd.getQuery());
        UUID tenantId = user.getTenantId();
        UUID customerId = user.getCustomerId();
        subscriptions.registerEntityCount(sessionRef, cmd.getCmdId(), tenantId, customerId, filter);
        long count = entityQueryService.countDevices(tenantId, customerId, filter);
        sendCmdUpdate(sessionRef, new EntityCountUpdate(cmd.getCmdId(), (int) count));
    }

    private void handleNotifications(WebSocketSessionRef sessionRef, NotificationsSubCmd cmd) {
        UUID recipientId = sessionRef.getSecurityPrincipal().getUserId();
        subscriptions.registerNotifications(sessionRef, cmd.getCmdId(), recipientId, cmd.getLimit(), cmd.getTypes());
        List<NotificationEntity> unread = notificationService.latestUnread(recipientId, cmd.getLimit(), cmd.getTypes());
        long totalUnread = notificationService.countUnread(recipientId);
        int sequence = subscriptions.nextNotificationSequence(sessionRef.getSessionId(), cmd.getCmdId());
        sendCmdUpdate(sessionRef, new NotificationsUpdate(cmd.getCmdId(), unread, null, (int) totalUnread, sequence));
    }

    private void handleNotificationsCount(WebSocketSessionRef sessionRef, NotificationsCountSubCmd cmd) {
        UUID recipientId = sessionRef.getSecurityPrincipal().getUserId();
        subscriptions.registerNotificationsCount(sessionRef, cmd.getCmdId(), recipientId);
        long totalUnread = notificationService.countUnread(recipientId);
        int sequence = subscriptions.nextNotificationSequence(sessionRef.getSessionId(), cmd.getCmdId());
        sendCmdUpdate(sessionRef, new NotificationsCountUpdate(cmd.getCmdId(), (int) totalUnread, sequence));
    }

    private void handleMarkNotificationsAsRead(WebSocketSessionRef sessionRef, MarkNotificationsAsReadCmd cmd) {
        UUID recipientId = sessionRef.getSecurityPrincipal().getUserId();
        if (cmd.getNotifications() == null) {
            return;
        }
        for (UUID notificationId : cmd.getNotifications()) {
            notificationService.markRead(recipientId, notificationId);
        }
    }

    private void handleMarkAllNotificationsAsRead(WebSocketSessionRef sessionRef, MarkAllNotificationsAsReadCmd cmd) {
        notificationService.markAllRead(sessionRef.getSecurityPrincipal().getUserId());
    }

    private void handleNotificationsUnsubscribe(WebSocketSessionRef sessionRef, WsCmd cmd) {
        String sessionId = sessionRef.getSessionId();
        subscriptions.unregisterNotifications(sessionId, cmd.getCmdId());
        subscriptions.unregisterNotificationsCount(sessionId, cmd.getCmdId());
    }

    private void handleHistory(WebSocketSessionRef sessionRef, GetHistoryCmd cmd) {
        DeviceEntity device = requireDevice(sessionRef, cmd.getEntityType(), cmd.getEntityId());
        Set<String> keys = parseKeys(cmd.getKeys());
        int limit = cmd.getLimit() > 0 ? cmd.getLimit() : 100;
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        for (String key : keys) {
            List<TelemetryPoint> points = telemetryService.history(
                    device.getTenantId(), device.getId(), List.of(key), cmd.getStartTs(), cmd.getEndTs(), limit, TelemetryOrder.DESC);
            List<Object[]> values = new ArrayList<>(points.size());
            for (TelemetryPoint point : points) {
                values.add(new Object[] {point.timestampMs(), String.valueOf(point.value())});
            }
            data.put(key, values);
        }
        sendUpdate(sessionRef, new TelemetrySubscriptionUpdate(cmd.getCmdId(), data));
    }

    private DeviceEntity requireDevice(WebSocketSessionRef sessionRef, SubscriptionCmd cmd) {
        return requireDevice(sessionRef, cmd.getEntityType(), cmd.getEntityId());
    }

    private DeviceEntity requireDevice(WebSocketSessionRef sessionRef, String entityType, String entityId) {
        if (!"DEVICE".equalsIgnoreCase(entityType)) {
            throw new IllegalArgumentException("Unsupported entity type: " + entityType);
        }
        return deviceService.requireScoped(UUID.fromString(entityId), auth(sessionRef.getSecurityPrincipal()));
    }

    private static JwtAuthenticationToken auth(SecurityUser user) {
        return new JwtAuthenticationToken(user);
    }

    private static Set<String> parseKeys(String keys) {
        if (!StringUtils.hasText(keys)) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(StringUtils.tokenizeToStringArray(keys, ",")));
    }

    private static AttributeScope parseScope(String scope) {
        if (!StringUtils.hasText(scope) || "ANY_SCOPE".equalsIgnoreCase(scope)) {
            return AttributeScope.CLIENT;
        }
        String normalized = scope.endsWith("_SCOPE") ? scope.substring(0, scope.length() - "_SCOPE".length()) : scope;
        return AttributeScope.valueOf(normalized.toUpperCase());
    }

    private static List<Object[]> pointList(long ts, Object value) {
        List<Object[]> points = new ArrayList<>(1);
        points.add(new Object[] {ts, String.valueOf(value)});
        return points;
    }
}
