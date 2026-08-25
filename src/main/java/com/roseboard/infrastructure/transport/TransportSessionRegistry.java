package com.roseboard.infrastructure.transport;

import com.roseboard.device.connectivity.DeviceConnectivityStateService;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.infrastructure.transport.cluster.TransportSessionRoute;
import com.roseboard.infrastructure.transport.cluster.TransportSessionStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class TransportSessionRegistry {
    private final ConcurrentHashMap<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> activeByDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> closedByDevice = new ConcurrentHashMap<>();
    private final Set<UUID> closedSessionIds = ConcurrentHashMap.newKeySet();
    private final TransportSessionStore sessionStore;
    private final DeviceConnectivityStateService connectivityState;
    private final TransportProperties transportProperties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "transport-session-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Clock clock;

    TransportSessionRegistry(TransportSessionStore sessionStore,
                             TransportProperties transportProperties,
                             DeviceConnectivityStateService connectivityState,
                             @Autowired(required = false) Clock clock) {
        this.sessionStore = sessionStore;
        this.transportProperties = transportProperties;
        this.connectivityState = connectivityState;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    SessionInfo register(SessionInfo sessionInfo, SessionMsgListener listener, Duration timeout) {
        return register(sessionInfo, listener, timeout, SessionMode.SYNC, "");
    }

    SessionInfo registerAsync(SessionInfo sessionInfo, SessionMsgListener listener, Duration idleTimeout) {
        return register(sessionInfo, listener, idleTimeout, SessionMode.ASYNC, "");
    }

    SessionInfo registerAsync(SessionInfo sessionInfo, SessionMsgListener listener,
                              Duration idleTimeout, String protocol) {
        return register(sessionInfo, listener, idleTimeout, SessionMode.ASYNC, protocol);
    }

    private SessionInfo register(SessionInfo sessionInfo, SessionMsgListener listener, Duration timeout,
                                 SessionMode mode, String protocol) {
        UUID deviceId = sessionInfo.deviceId();
        UUID existing = activeByDevice.get(deviceId);
        if (existing != null) {
            closeEntry(existing, CloseReason.REPLACED);
        }
        closedByDevice.remove(deviceId);

        long now = clock.millis();
        SessionEntry entry = new SessionEntry(sessionInfo, listener, now, mode, protocol);
        sessions.put(entry.sessionId, entry);
        activeByDevice.put(deviceId, entry.sessionId);
        entry.scheduleTimeout(scheduler, timeout, () -> closeEntry(entry.sessionId, CloseReason.TIMEOUT));
        persistRoute(entry);
        if (mode == SessionMode.ASYNC) {
            safeStateUpdate(() -> connectivityState.onConnect(entry.tenantId, entry.deviceId, now));
        }
        return entry.toSessionInfo();
    }

    void notifyGetAttributesResponse(UUID sessionId, GetAttributeResponseMsg msg) {
        SessionEntry entry = requireOpenEntry(sessionId);
        synchronized (entry) {
            entry.listener.onGetAttributesResponse(msg);
        }
    }

    SessionInfo snapshot(UUID sessionId) {
        SessionEntry entry = requireEntry(sessionId);
        synchronized (entry) {
            return entry.toSessionInfo();
        }
    }

    void recordActivity(UUID sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.lastActivityAt = clock.millis();
            entry.refreshTimeout();
        }
        persistRoute(entry);
        safeStateUpdate(() -> connectivityState.onActivity(entry.tenantId, entry.deviceId, clock.millis()));
    }

    void closeSession(UUID sessionId) {
        closeEntry(sessionId, CloseReason.EXPLICIT);
    }

    void setSubscribedToRpc(UUID sessionId, boolean subscribed) {
        requireOpenEntry(sessionId).subscribedToRpc = subscribed;
    }

    public DeliverResult deliver(UUID deviceId, TransportToDevicePayload message) {
        SessionEntry entry = activeEntry(deviceId);
        if (entry == null) {
            return closedByDevice.containsKey(deviceId) ? DeliverResult.SESSION_CLOSED : DeliverResult.NO_SESSION;
        }
        synchronized (entry) {
            if (entry.closed) {
                return DeliverResult.SESSION_CLOSED;
            }
            if (entry.mode == SessionMode.ASYNC && !entry.subscribedToRpc) {
                return DeliverResult.NO_SESSION;
            }
            entry.listener.onMessage(message);
            entry.lastActivityAt = clock.millis();
            entry.refreshTimeout();
            if (entry.mode == SessionMode.SYNC) {
                closeEntry(entry.sessionId, CloseReason.DELIVERED);
            }
        }
        return DeliverResult.DELIVERED;
    }

    void setSubscribedToAttributes(UUID sessionId, boolean subscribed) {
        requireOpenEntry(sessionId).subscribedToAttributes = subscribed;
    }

    public DeliverResult deliverAttributeUpdate(UUID deviceId, AttributeUpdateNotificationMsg message) {
        SessionEntry entry = activeEntry(deviceId);
        if (entry == null) {
            return closedByDevice.containsKey(deviceId) ? DeliverResult.SESSION_CLOSED : DeliverResult.NO_SESSION;
        }
        synchronized (entry) {
            if (entry.closed || !entry.subscribedToAttributes) {
                return entry.closed ? DeliverResult.SESSION_CLOSED : DeliverResult.NO_SESSION;
            }
            entry.listener.onAttributeUpdate(message);
            entry.lastActivityAt = clock.millis();
            entry.refreshTimeout();
            if (entry.mode == SessionMode.SYNC) {
                closeEntry(entry.sessionId, CloseReason.DELIVERED);
            }
        }
        return DeliverResult.DELIVERED;
    }

    public DeliverResult deliverToServerRpcResponse(UUID deviceId, ToServerRpcResponseMsg message) {
        SessionEntry entry = activeEntry(deviceId);
        if (entry == null) {
            return closedByDevice.containsKey(deviceId) ? DeliverResult.SESSION_CLOSED : DeliverResult.NO_SESSION;
        }
        synchronized (entry) {
            if (entry.closed) {
                return DeliverResult.SESSION_CLOSED;
            }
            entry.listener.onToServerRpcResponse(message);
            entry.lastActivityAt = clock.millis();
            entry.refreshTimeout();
            if (entry.mode == SessionMode.SYNC) {
                closeEntry(entry.sessionId, CloseReason.DELIVERED);
            }
        }
        return DeliverResult.DELIVERED;
    }

    int nextRequestId(UUID sessionId) {
        return requireOpenEntry(sessionId).nextRequestId();
    }

    void associateRpc(UUID sessionId, int requestId, UUID rpcId) {
        requireOpenEntry(sessionId).associateRpc(requestId, rpcId);
    }

    Optional<UUID> resolveRpcId(UUID sessionId, int requestId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.resolveRpc(requestId));
    }

    Optional<UUID> activeSessionId(UUID deviceId) {
        SessionEntry entry = activeEntry(deviceId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return entry.closed ? Optional.empty() : Optional.of(entry.sessionId);
        }
    }

    Optional<String> activeNodeId(UUID deviceId) {
        SessionEntry entry = activeEntry(deviceId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entry.closed) {
                return Optional.empty();
            }
            return Optional.ofNullable(entry.nodeId);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void persistRoute(SessionEntry entry) {
        String nodeId = entry.nodeId != null && !entry.nodeId.isBlank()
                ? entry.nodeId
                : transportProperties.getCluster().resolvedNodeId();
        sessionStore.save(new TransportSessionRoute(
                entry.deviceId,
                entry.tenantId,
                entry.sessionId,
                nodeId,
                entry.protocol,
                entry.lastActivityAt));
    }

    private SessionEntry activeEntry(UUID deviceId) {
        UUID sessionId = activeByDevice.get(deviceId);
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    private void closeEntry(UUID sessionId, CloseReason reason) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }
        SessionMsgListener timeoutListener = null;
        UUID notifySessionId = null;
        synchronized (entry) {
            if (entry.closed) {
                return;
            }
            if (reason == CloseReason.TIMEOUT) {
                timeoutListener = entry.listener;
                notifySessionId = entry.sessionId;
            }
            entry.closed = true;
            entry.lastActivityAt = clock.millis();
            ScheduledFuture<?> future = entry.timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
            activeByDevice.remove(entry.deviceId, entry.sessionId);
            sessions.remove(entry.sessionId, entry);
            closedSessionIds.add(entry.sessionId);
            if (reason.tracksClosedState()) {
                closedByDevice.put(entry.deviceId, entry.sessionId);
            }
        }
        sessionStore.remove(entry.deviceId);
        if (entry.mode == SessionMode.ASYNC) {
            safeStateUpdate(() -> connectivityState.onDisconnect(
                    entry.tenantId, entry.deviceId, entry.lastActivityAt));
        }
        if (timeoutListener != null) {
            timeoutListener.onSessionClose(SessionCloseNotification.timeout(notifySessionId));
        }
    }

    private void safeStateUpdate(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException ignored) {
            // Connectivity state must not break transport session lifecycle.
        }
    }

    private SessionEntry requireEntry(UUID sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown transport session: " + sessionId);
        }
        return entry;
    }
    private SessionEntry requireOpenEntry(UUID sessionId) {
        if (closedSessionIds.contains(sessionId)) {
            throw new IllegalStateException("Transport session is closed: " + sessionId);
        }
        SessionEntry entry = requireEntry(sessionId);
        synchronized (entry) {
            if (entry.closed) {
                throw new IllegalStateException("Transport session is closed: " + sessionId);
            }
        }
        return entry;
    }

    private enum SessionMode {
        SYNC, ASYNC
    }

    private enum CloseReason {
        EXPLICIT,
        TIMEOUT,
        REPLACED,
        DELIVERED;

        boolean tracksClosedState() {
            return this == EXPLICIT || this == TIMEOUT;
        }
    }

    private static final class SessionEntry {
        private final UUID sessionId;
        private final UUID deviceId;
        private final UUID tenantId;
        private final UUID customerId;
        private final UUID deviceProfileId;
        private final String deviceName;
        private final String deviceType;
        private final boolean gateway;
        private final String nodeId;
        private final String protocol;
        private final SessionMsgListener listener;
        private final SessionMode mode;
        private final AtomicInteger requestIdSequence = new AtomicInteger(0);
        private final Map<Integer, UUID> rpcByRequestId = new ConcurrentHashMap<>();
        private final long createdAt;
        private volatile long lastActivityAt;
        private volatile ScheduledExecutorService timeoutScheduler;
        private volatile Runnable timeoutAction;
        private volatile long timeoutMillis;
        private volatile boolean closed;
        private volatile boolean subscribedToAttributes;
        private volatile boolean subscribedToRpc;
        private volatile ScheduledFuture<?> timeoutFuture;

        private SessionEntry(SessionInfo sessionInfo, SessionMsgListener listener, long now,
                             SessionMode mode, String protocol) {
            this.sessionId = sessionInfo.sessionId();
            this.deviceId = sessionInfo.deviceId();
            this.tenantId = sessionInfo.tenantId();
            this.customerId = sessionInfo.customerId();
            this.deviceProfileId = sessionInfo.deviceProfileId();
            this.deviceName = sessionInfo.deviceName();
            this.deviceType = sessionInfo.deviceType();
            this.gateway = sessionInfo.gateway();
            this.nodeId = sessionInfo.nodeId();
            this.protocol = protocol == null ? "" : protocol;
            this.listener = listener;
            this.mode = mode;
            this.createdAt = sessionInfo.createdAt() > 0 ? sessionInfo.createdAt() : now;
            this.lastActivityAt = sessionInfo.lastActivityAt() > 0 ? sessionInfo.lastActivityAt() : now;
        }

        private void scheduleTimeout(ScheduledExecutorService scheduler, Duration timeout, Runnable onTimeout) {
            timeoutScheduler = scheduler;
            timeoutAction = onTimeout;
            timeoutMillis = Math.max(1L, timeout.toMillis());
            refreshTimeout();
        }

        private void refreshTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
            timeoutFuture = timeoutScheduler.schedule(timeoutAction, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private int nextRequestId() {
            return requestIdSequence.incrementAndGet();
        }

        private void associateRpc(int requestId, UUID rpcId) {
            rpcByRequestId.put(requestId, rpcId);
        }

        private UUID resolveRpc(int requestId) {
            return rpcByRequestId.get(requestId);
        }

        private SessionInfo toSessionInfo() {
            return new SessionInfo(
                    sessionId,
                    deviceId,
                    tenantId,
                    customerId,
                    deviceProfileId,
                    deviceName,
                    deviceType,
                    gateway,
                    nodeId,
                    createdAt,
                    lastActivityAt);
        }
    }
}
