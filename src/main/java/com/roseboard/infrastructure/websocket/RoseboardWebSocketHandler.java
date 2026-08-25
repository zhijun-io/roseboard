package com.roseboard.infrastructure.websocket;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.security.jwt.JwtAuthenticationProvider;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.apikey.ApiKeyAuthenticationProvider;
import com.roseboard.infrastructure.websocket.cmd.AuthCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCommandsWrapper;
import com.roseboard.infrastructure.websocket.cmd.plugin.NotificationCmdsWrapper;
import com.roseboard.infrastructure.websocket.cmd.plugin.TelemetryCmdsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.security.InvalidParameterException;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RoseboardWebSocketHandler extends TextWebSocketHandler implements WebSocketMsgEndpoint {
    private static final Logger log = LoggerFactory.getLogger(RoseboardWebSocketHandler.class);

    private final ConcurrentMap<String, SessionMetaData> internalSessionMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionMetaData> pendingSessionMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> externalSessionMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingAuthTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSessionRef> blacklistedSessions = new ConcurrentHashMap<>();

    private final WebSocketService webSocketService;
    private final JwtAuthenticationProvider jwtAuthenticationProvider;
    private final ApiKeyAuthenticationProvider apiKeyAuthenticationProvider;
    private final WebSocketLimitTracker limitTracker;
    private final WebSocketUpdateRateLimiter updateRateLimiter;
    private final TenantProfileWsLimits wsLimits;

    @Value("${server.ws.max_queue_messages_per_session:1000}")
    private int maxQueueMessagesPerSession;
    @Value("${server.ws.auth_timeout_ms:10000}")
    private int authTimeoutMs;
    @Value("${server.ws.ping_timeout:30000}")
    private long pingTimeout;

    private ScheduledExecutorService scheduler;

    public RoseboardWebSocketHandler(@Lazy WebSocketService webSocketService,
                                     JwtAuthenticationProvider jwtAuthenticationProvider,
                                     ApiKeyAuthenticationProvider apiKeyAuthenticationProvider,
                                     WebSocketLimitTracker limitTracker,
                                     WebSocketUpdateRateLimiter updateRateLimiter,
                                     TenantProfileWsLimits wsLimits) {
        this.webSocketService = webSocketService;
        this.jwtAuthenticationProvider = jwtAuthenticationProvider;
        this.apiKeyAuthenticationProvider = apiKeyAuthenticationProvider;
        this.limitTracker = limitTracker;
        this.updateRateLimiter = updateRateLimiter;
        this.wsLimits = wsLimits;
    }

    @PostConstruct
    void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ws-handler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        internalSessionMap.clear();
        pendingAuthTimeouts.values().forEach(future -> future.cancel(false));
        pendingAuthTimeouts.clear();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSessionRef sessionRef = toRef(session);
        log.debug("[{}] Session opened from {}", sessionRef.getSessionId(), session.getRemoteAddress());
        if (sessionRef.getSecurityPrincipal() != null) {
            if (!establishAuthenticatedSession(session, sessionRef)) {
                return;
            }
        } else {
            SessionMetaData sessionMd = new SessionMetaData(session, sessionRef, maxQueueMessagesPerSession);
            pendingSessionMap.put(session.getId(), sessionMd);
            externalSessionMap.put(sessionRef.getSessionId(), session.getId());
            scheduleAuthTimeout(session.getId(), sessionRef);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionMetaData sessionMd = getSessionMd(session.getId());
        if (sessionMd == null) {
            session.close(CloseStatus.SERVER_ERROR.withReason("Session not found"));
            return;
        }
        sessionMd.onMsg(message.getPayload());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        SessionMetaData sessionMd = getSessionMd(session.getId());
        if (sessionMd != null) {
            sessionMd.touchActivity();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelAuthTimeout(session.getId());
        SessionMetaData sessionMd = internalSessionMap.remove(session.getId());
        boolean established = sessionMd != null;
        if (sessionMd == null) {
            sessionMd = pendingSessionMap.remove(session.getId());
        }
        if (sessionMd != null) {
            externalSessionMap.remove(sessionMd.sessionRef.getSessionId());
            blacklistedSessions.remove(sessionMd.sessionRef.getSessionId());
            if (established && sessionMd.sessionRef.getSecurityPrincipal() != null) {
                limitTracker.releaseSession(sessionMd.sessionRef);
                webSocketService.handleSessionClosed(sessionMd.sessionRef);
            }
            log.debug("{} Session closed: {}", sessionMd.sessionRef.getSessionId(), status);
        }
    }

    @Override
    public void send(WebSocketSessionRef sessionRef, int subscriptionId, String msg) throws IOException {
        String internalId = externalSessionMap.get(sessionRef.getSessionId());
        if (internalId == null) {
            return;
        }
        SessionMetaData sessionMd = internalSessionMap.get(internalId);
        if (sessionMd == null) {
            return;
        }
        SecurityUser user = sessionRef.getSecurityPrincipal();
        UUID tenantId = user == null ? null : user.getTenantId();
        if (tenantId != null && !updateRateLimiter.check(tenantId, sessionRef.getSessionId())) {
            if (blacklistedSessions.putIfAbsent(sessionRef.getSessionId(), sessionRef) == null) {
                log.info("{} WS update rate limit exceeded", sessionRef.getSessionId());
                sessionMd.sendMsgDirect(rateLimitError(subscriptionId));
            }
            return;
        }
        blacklistedSessions.remove(sessionRef.getSessionId());
        sessionMd.sendMsg(msg);
    }

    private static String rateLimitError(int subscriptionId) {
        return "{\"subscriptionId\":" + subscriptionId
                + ",\"errorCode\":" + SubscriptionErrorCode.TOO_MANY_UPDATES.getCode()
                + ",\"errorMsg\":\"" + SubscriptionErrorCode.TOO_MANY_UPDATES.getDefaultMsg() + "\"}";
    }

    @Override
    public void close(WebSocketSessionRef sessionRef, CloseStatus reason) throws IOException {
        String internalId = externalSessionMap.get(sessionRef.getSessionId());
        if (internalId == null) {
            return;
        }
        SessionMetaData sessionMd = getSessionMd(internalId);
        if (sessionMd != null && sessionMd.session.isOpen()) {
            sessionMd.session.close(reason);
        }
    }

    @Override
    public boolean isOpen(String externalSessionId) {
        String internalId = externalSessionMap.get(externalSessionId);
        if (internalId == null) {
            return false;
        }
        SessionMetaData sessionMd = getSessionMd(internalId);
        return sessionMd != null && sessionMd.session.isOpen();
    }

    @Override
    public void sendPing(WebSocketSessionRef sessionRef, long currentTime) throws IOException {
        String internalId = externalSessionMap.get(sessionRef.getSessionId());
        if (internalId == null) {
            return;
        }
        SessionMetaData sessionMd = internalSessionMap.get(internalId);
        if (sessionMd != null) {
            sessionMd.sendPing(currentTime);
        }
    }

    private void processMsg(SessionMetaData sessionMd, String msg) throws IOException {
        WebSocketSessionRef sessionRef = sessionMd.sessionRef;
        WsCommandsWrapper cmdsWrapper = parseCommands(sessionRef, msg);
        if (cmdsWrapper == null) {
            return;
        }

        if (sessionRef.getSecurityPrincipal() != null) {
            webSocketService.handleCommands(sessionRef, cmdsWrapper);
            return;
        }

        AuthCmd authCmd = cmdsWrapper.getAuthCmd();
        if (authCmd == null) {
            close(sessionRef, CloseStatus.POLICY_VIOLATION.withReason("Auth cmd is missing"));
            return;
        }

        SecurityUser securityUser = authenticate(sessionRef, authCmd);
        if (securityUser == null) {
            return;
        }
        sessionRef.setSecurityPrincipal(securityUser);
        cancelAuthTimeout(sessionMd.session.getId());
        if (!establishAuthenticatedSession(sessionMd.session, sessionRef)) {
            return;
        }
        webSocketService.handleCommands(sessionRef, cmdsWrapper);
    }

    private WsCommandsWrapper parseCommands(WebSocketSessionRef sessionRef, String msg) throws IOException {
        try {
            return switch (sessionRef.getSessionType()) {
                case GENERAL -> JacksonUtils.fromString(msg, WsCommandsWrapper.class);
                case TELEMETRY -> JacksonUtils.fromString(msg, TelemetryCmdsWrapper.class).toCommonCmdsWrapper();
                case NOTIFICATIONS -> JacksonUtils.fromString(msg, NotificationCmdsWrapper.class).toCommonCmdsWrapper();
            };
        } catch (Exception exception) {
            log.debug("{} Failed to decode WS cmd: {}", sessionRef.getSessionId(), exception.getMessage());
            if (sessionRef.getSecurityPrincipal() != null) {
                webSocketService.sendError(sessionRef, 1, SubscriptionErrorCode.BAD_REQUEST,
                        "Failed to parse the payload");
            } else {
                close(sessionRef, CloseStatus.BAD_DATA.withReason(exception.getMessage()));
            }
            return null;
        }
    }

    private SecurityUser authenticate(WebSocketSessionRef sessionRef, AuthCmd authCmd) throws IOException {
        try {
            if (StringUtils.hasText(authCmd.getApiKey())) {
                return apiKeyAuthenticationProvider.authenticateApiKey(authCmd.getApiKey());
            }
            return jwtAuthenticationProvider.authenticateAccessToken(authCmd.getToken());
        } catch (Exception exception) {
            close(sessionRef, CloseStatus.BAD_DATA.withReason(exception.getMessage()));
            return null;
        }
    }

    private boolean establishAuthenticatedSession(WebSocketSession session, WebSocketSessionRef sessionRef)
            throws IOException {
        if (!limitTracker.tryAcquireSession(session, sessionRef)) {
            return false;
        }
        int queueLimit = maxQueueMessagesPerSession;
        SecurityUser user = sessionRef.getSecurityPrincipal();
        if (user != null) {
            int profileQueueLimit = wsLimits.wsMsgQueueLimitPerSession(user.getTenantId());
            if (profileQueueLimit > 0) {
                queueLimit = profileQueueLimit;
            }
        }
        SessionMetaData sessionMd = pendingSessionMap.remove(session.getId());
        if (sessionMd == null) {
            sessionMd = new SessionMetaData(session, sessionRef, queueLimit);
        } else {
            sessionMd.setMaxQueueMessages(queueLimit);
        }
        internalSessionMap.put(session.getId(), sessionMd);
        externalSessionMap.put(sessionRef.getSessionId(), session.getId());
        webSocketService.handleSessionOpened(sessionRef);
        return true;
    }

    private WebSocketSessionRef toRef(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            throw new InvalidParameterException("WebSocket URI is missing");
        }
        String path = uri.getPath();
        WebSocketSessionType sessionType = resolveSessionType(path);

        SecurityUser securityUser = null;
        String query = uri.getQuery();
        if (StringUtils.hasText(query)) {
            String token = extractQueryParam(query, "token");
            if (StringUtils.hasText(token)) {
                securityUser = jwtAuthenticationProvider.authenticateAccessToken(token);
            }
        }

        return new WebSocketSessionRef(
                UUID.randomUUID().toString(),
                securityUser,
                session.getLocalAddress(),
                session.getRemoteAddress(),
                sessionType);
    }

    private static WebSocketSessionType resolveSessionType(String path) {
        if (WebSocketConfiguration.WS_API_ENDPOINT.equals(path)) {
            return WebSocketSessionType.GENERAL;
        }
        if (path.startsWith(WebSocketConfiguration.WS_PLUGINS_ENDPOINT)) {
            String pluginName = path.substring(WebSocketConfiguration.WS_PLUGINS_ENDPOINT.length());
            return WebSocketSessionType.forPluginName(pluginName)
                    .orElseThrow(() -> new InvalidParameterException("Unknown session type: " + pluginName));
        }
        throw new InvalidParameterException("Unknown WebSocket path: " + path);
    }

    private static String extractQueryParam(String query, String paramName) {
        for (String param : query.split("&")) {
            if (param.startsWith(paramName + "=")) {
                return param.substring(paramName.length() + 1);
            }
        }
        return null;
    }

    private SessionMetaData getSessionMd(String internalSessionId) {
        SessionMetaData sessionMd = internalSessionMap.get(internalSessionId);
        if (sessionMd == null) {
            sessionMd = pendingSessionMap.get(internalSessionId);
        }
        return sessionMd;
    }

    private void scheduleAuthTimeout(String internalSessionId, WebSocketSessionRef sessionRef) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                SessionMetaData sessionMd = pendingSessionMap.remove(internalSessionId);
                if (sessionMd == null) {
                    return;
                }
                if (sessionMd.session.isOpen()) {
                    sessionMd.session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication timeout"));
                }
            } catch (IOException exception) {
                log.debug("Failed to close unauthenticated session {}", sessionRef.getSessionId(), exception);
            } finally {
                externalSessionMap.remove(sessionRef.getSessionId());
                pendingAuthTimeouts.remove(internalSessionId);
            }
        }, authTimeoutMs, TimeUnit.MILLISECONDS);
        pendingAuthTimeouts.put(internalSessionId, future);
    }

    private void cancelAuthTimeout(String internalSessionId) {
        ScheduledFuture<?> future = pendingAuthTimeouts.remove(internalSessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private final class SessionMetaData {
        private final WebSocketSession session;
        private final WebSocketSessionRef sessionRef;
        private final Queue<String> outboundMsgQueue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean sending = new AtomicBoolean(false);
        private volatile int maxQueueMessages;
        private volatile int outboundQueueSize;
        private volatile long lastActivityTime = System.currentTimeMillis();

        SessionMetaData(WebSocketSession session, WebSocketSessionRef sessionRef, int maxQueueMessages) {
            this.session = session;
            this.sessionRef = sessionRef;
            this.maxQueueMessages = maxQueueMessages;
        }

        void setMaxQueueMessages(int maxQueueMessages) {
            this.maxQueueMessages = maxQueueMessages;
        }

        void onMsg(String msg) throws IOException {
            touchActivity();
            processMsg(this, msg);
        }

        void touchActivity() {
            lastActivityTime = System.currentTimeMillis();
        }

        void sendPing(long currentTime) throws IOException {
            long timeSinceLastActivity = currentTime - lastActivityTime;
            if (timeSinceLastActivity >= pingTimeout) {
                log.debug("{} Closing session due to ping timeout", sessionRef.getSessionId());
                close(sessionRef, CloseStatus.SESSION_NOT_RELIABLE.withReason("Ping timeout"));
            } else if (timeSinceLastActivity >= pingTimeout / DefaultWebSocketService.NUMBER_OF_PING_ATTEMPTS) {
                if (session.isOpen()) {
                    session.sendMessage(new PingMessage());
                }
            }
        }

        void sendMsg(String msg) throws IOException {
            if (outboundQueueSize >= maxQueueMessages) {
                close(sessionRef, CloseStatus.POLICY_VIOLATION.withReason("Max pending updates limit reached"));
                return;
            }
            outboundMsgQueue.add(msg);
            outboundQueueSize++;
            drainOutboundQueue();
        }

        void sendMsgDirect(String msg) throws IOException {
            if (!session.isOpen()) {
                return;
            }
            session.sendMessage(new TextMessage(msg));
            touchActivity();
        }

        private void drainOutboundQueue() throws IOException {
            if (!sending.compareAndSet(false, true)) {
                return;
            }
            try {
                while (session.isOpen()) {
                    String msg = outboundMsgQueue.poll();
                    if (msg == null) {
                        break;
                    }
                    outboundQueueSize--;
                    session.sendMessage(new TextMessage(msg));
                    touchActivity();
                }
            } finally {
                sending.set(false);
                if (!outboundMsgQueue.isEmpty()) {
                    drainOutboundQueue();
                }
            }
        }
    }
}
