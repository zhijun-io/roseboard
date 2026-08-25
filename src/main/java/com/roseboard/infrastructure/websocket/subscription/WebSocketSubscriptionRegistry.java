package com.roseboard.infrastructure.websocket.subscription;

import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.infrastructure.websocket.WebSocketSessionRef;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataMapper;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityKeyType;
import com.roseboard.infrastructure.websocket.sub.TelemetrySubscriptionUpdate;
import com.roseboard.device.query.EntityDataKeys;
import com.roseboard.device.query.filter.DeviceTypeFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Component
public class WebSocketSubscriptionRegistry {
    private final ConcurrentMap<String, List<TimeseriesSubscription>> timeseries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AttributeSubscription>> attributes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<EntityDataSubscription>> entityData = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<EntityCountSubscription>> entityCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<NotificationsSubscription>> notifications = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<NotificationsCountSubscription>> notificationCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> notificationSequences = new ConcurrentHashMap<>();

    public void registerTimeseries(WebSocketSessionRef session, int cmdId, UUID deviceId, Set<String> keys) {
        register(timeseries, session.getSessionId(), new TimeseriesSubscription(cmdId, deviceId, keys));
    }

    public void registerAttributes(WebSocketSessionRef session, int cmdId, UUID deviceId,
                                   AttributeScope scope, Set<String> keys) {
        register(attributes, session.getSessionId(), new AttributeSubscription(cmdId, deviceId, scope, keys));
    }

    public void registerEntityData(WebSocketSessionRef session, int cmdId, UUID deviceId, EntityDataKeys keys) {
        register(entityData, session.getSessionId(), new EntityDataSubscription(cmdId, deviceId, keys));
    }

    public void registerEntityCount(WebSocketSessionRef session, int cmdId,
                                    UUID tenantId, UUID customerId, DeviceTypeFilter filter) {
        register(entityCounts, session.getSessionId(), new EntityCountSubscription(cmdId, tenantId, customerId, filter));
    }

    public void registerNotifications(WebSocketSessionRef session, int cmdId,
                                      UUID recipientId, int limit, Set<String> types) {
        register(notifications, session.getSessionId(),
                new NotificationsSubscription(cmdId, recipientId, limit > 0 ? limit : 10, types));
    }

    public void registerNotificationsCount(WebSocketSessionRef session, int cmdId, UUID recipientId) {
        register(notificationCounts, session.getSessionId(), new NotificationsCountSubscription(cmdId, recipientId));
    }

    public void unregisterTimeseries(String sessionId, int cmdId) {
        removeByCmdId(timeseries.get(sessionId), cmdId);
    }

    public void unregisterAttributes(String sessionId, int cmdId) {
        removeByCmdId(attributes.get(sessionId), cmdId);
    }

    public void unregisterEntityData(String sessionId, int cmdId) {
        removeByCmdId(entityData.get(sessionId), cmdId);
    }

    public void unregisterEntityCount(String sessionId, int cmdId) {
        removeByCmdId(entityCounts.get(sessionId), cmdId);
    }

    public void unregisterNotifications(String sessionId, int cmdId) {
        removeByCmdId(notifications.get(sessionId), cmdId);
        clearSequence(sessionId, cmdId);
    }

    public void unregisterNotificationsCount(String sessionId, int cmdId) {
        removeByCmdId(notificationCounts.get(sessionId), cmdId);
        clearSequence(sessionId, cmdId);
    }

    public void removeSession(String sessionId) {
        timeseries.remove(sessionId);
        attributes.remove(sessionId);
        entityData.remove(sessionId);
        entityCounts.remove(sessionId);
        notifications.remove(sessionId);
        notificationCounts.remove(sessionId);
        notificationSequences.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
    }

    public List<MatchedUpdate> matchTelemetry(UUID deviceId, String key, long ts, Object value) {
        List<MatchedUpdate> matched = new ArrayList<>();
        forEachSubscription(timeseries, (sessionId, sub) -> {
            if (sub.deviceId().equals(deviceId) && (sub.keys().isEmpty() || sub.keys().contains(key))) {
                matched.add(new MatchedUpdate(sessionId, telemetryUpdate(sub.cmdId(), key, ts, value)));
            }
        });
        return matched;
    }

    public List<MatchedUpdate> matchAttributes(UUID deviceId, AttributeScope scope, String key, long ts, Object value) {
        List<MatchedUpdate> matched = new ArrayList<>();
        forEachSubscription(attributes, (sessionId, sub) -> {
            if (sub.deviceId().equals(deviceId)
                    && sub.scope() == scope
                    && (sub.keys().isEmpty() || sub.keys().contains(key))) {
                matched.add(new MatchedUpdate(sessionId, telemetryUpdate(sub.cmdId(), key, ts, value)));
            }
        });
        return matched;
    }

    public List<MatchedEntityUpdate> matchEntityData(UUID deviceId, EntityKeyType keyType, String key,
                                                     long ts, Object value) {
        List<MatchedEntityUpdate> matched = new ArrayList<>();
        forEachSubscription(entityData, (sessionId, sub) -> {
            if (sub.deviceId().equals(deviceId) && EntityDataMapper.subscribes(sub.keys(), keyType, key)) {
                matched.add(new MatchedEntityUpdate(sessionId,
                        EntityDataMapper.toIncrementalUpdate(sub.cmdId(), deviceId, keyType, key, ts, value)));
            }
        });
        return matched;
    }

    public List<MatchedNotificationsSubscription> matchNotificationsRecipient(UUID recipientId) {
        List<MatchedNotificationsSubscription> matched = new ArrayList<>();
        forEachSubscription(notifications, (sessionId, sub) -> {
            if (sub.recipientId().equals(recipientId)) {
                matched.add(new MatchedNotificationsSubscription(sessionId, sub));
            }
        });
        return matched;
    }

    public List<MatchedNotificationsCountSubscription> matchNotificationsCountRecipient(UUID recipientId) {
        List<MatchedNotificationsCountSubscription> matched = new ArrayList<>();
        forEachSubscription(notificationCounts, (sessionId, sub) -> {
            if (sub.recipientId().equals(recipientId)) {
                matched.add(new MatchedNotificationsCountSubscription(sessionId, sub));
            }
        });
        return matched;
    }

    public int nextNotificationSequence(String sessionId, int cmdId) {
        return notificationSequences
                .computeIfAbsent(sequenceKey(sessionId, cmdId), ignored -> new AtomicInteger(0))
                .incrementAndGet();
    }

    private void clearSequence(String sessionId, int cmdId) {
        notificationSequences.remove(sequenceKey(sessionId, cmdId));
    }

    private static String sequenceKey(String sessionId, int cmdId) {
        return sessionId + ":" + cmdId;
    }

    public List<MatchedEntityCountSubscription> allEntityCountSubscriptions() {
        List<MatchedEntityCountSubscription> all = new ArrayList<>();
        forEachSubscription(entityCounts, (sessionId, sub) -> all.add(new MatchedEntityCountSubscription(sessionId, sub)));
        return all;
    }

    private static TelemetrySubscriptionUpdate telemetryUpdate(int cmdId, String key, long ts, Object value) {
        return new TelemetrySubscriptionUpdate(cmdId, TelemetrySubscriptionUpdate.singlePoint(key, ts, value));
    }

    private static <T extends CmdSubscription> void register(ConcurrentMap<String, List<T>> map,
                                                             String sessionId, T subscription) {
        List<T> subs = map.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>());
        removeByCmdId(subs, subscription.cmdId());
        subs.add(subscription);
    }

    private static void removeByCmdId(List<? extends CmdSubscription> subs, int cmdId) {
        if (subs != null) {
            subs.removeIf(sub -> sub.cmdId() == cmdId);
        }
    }

    private static <T extends CmdSubscription> void forEachSubscription(
            ConcurrentMap<String, List<T>> map, BiConsumer<String, T> consumer) {
        map.forEach((sessionId, subs) -> subs.forEach(sub -> consumer.accept(sessionId, sub)));
    }

    private interface CmdSubscription {
        int cmdId();
    }

    public record TimeseriesSubscription(int cmdId, UUID deviceId, Set<String> keys) implements CmdSubscription {
    }

    public record AttributeSubscription(int cmdId, UUID deviceId, AttributeScope scope, Set<String> keys)
            implements CmdSubscription {
    }

    public record EntityDataSubscription(int cmdId, UUID deviceId, EntityDataKeys keys) implements CmdSubscription {
    }

    public record EntityCountSubscription(int cmdId, UUID tenantId, UUID customerId, DeviceTypeFilter filter)
            implements CmdSubscription {
    }

    public record NotificationsSubscription(int cmdId, UUID recipientId, int limit, Set<String> types)
            implements CmdSubscription {
    }

    public record NotificationsCountSubscription(int cmdId, UUID recipientId) implements CmdSubscription {
    }

    public record MatchedUpdate(String sessionId, TelemetrySubscriptionUpdate update) {
    }

    public record MatchedEntityUpdate(String sessionId, EntityDataUpdate update) {
    }

    public record MatchedEntityCountSubscription(String sessionId, EntityCountSubscription subscription) {
    }

    public record MatchedNotificationsSubscription(String sessionId, NotificationsSubscription subscription) {
    }

    public record MatchedNotificationsCountSubscription(String sessionId, NotificationsCountSubscription subscription) {
    }
}
