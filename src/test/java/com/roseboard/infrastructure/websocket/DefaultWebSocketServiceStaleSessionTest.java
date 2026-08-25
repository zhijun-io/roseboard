package com.roseboard.infrastructure.websocket;

import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.websocket.subscription.WebSocketSubscriptionRegistry;
import com.roseboard.infrastructure.websocket.sub.TelemetrySubscriptionUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultWebSocketServiceStaleSessionTest {
    @Mock
    private WebSocketMsgEndpoint msgEndpoint;
    @Mock
    private WebSocketSubscriptionRegistry subscriptions;
    @Mock
    private WebSocketLimitTracker limitTracker;
    @Mock
    private SecurityUser securityUser;

    private DefaultWebSocketService service;

    @BeforeEach
    void setUp() {
        service = new DefaultWebSocketService(
                null, null, null, null, null, subscriptions, limitTracker, msgEndpoint);
    }

    @Test
    void cleanupIfStaleReleasesSessionAndSubscriptionLimits() {
        String sessionId = UUID.randomUUID().toString();
        WebSocketSessionRef sessionRef = sessionRef(sessionId);
        service.handleSessionOpened(sessionRef);
        when(msgEndpoint.isOpen(sessionId)).thenReturn(false);

        service.cleanupIfStale(sessionId);

        verify(limitTracker).releaseSession(sessionRef);
        verify(limitTracker).releaseSessionSubscriptions(sessionRef);
        verify(subscriptions).removeSession(sessionId);
    }

    @Test
    void sendUpdateBySessionIdTriggersCleanupForClosedSession() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        WebSocketSessionRef sessionRef = sessionRef(sessionId);
        service.handleSessionOpened(sessionRef);
        when(msgEndpoint.isOpen(sessionId)).thenReturn(false);

        service.sendUpdateBySessionId(sessionId, new TelemetrySubscriptionUpdate(1, Map.of()));

        verify(limitTracker).releaseSession(sessionRef);
        verify(limitTracker).releaseSessionSubscriptions(sessionRef);
        verify(subscriptions).removeSession(sessionId);
        verify(msgEndpoint, never()).send(any(), anyInt(), any());
    }

    @Test
    void sendUpdateReleasesLimitsWhenSendFails() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        WebSocketSessionRef sessionRef = sessionRef(sessionId);
        service.handleSessionOpened(sessionRef);
        when(msgEndpoint.isOpen(sessionId)).thenReturn(true, false);
        doThrow(new IOException("closed")).when(msgEndpoint).send(eq(sessionRef), eq(1), any());

        service.sendUpdate(sessionRef, new TelemetrySubscriptionUpdate(1, Map.of()));

        verify(limitTracker).releaseSession(sessionRef);
        verify(limitTracker).releaseSessionSubscriptions(sessionRef);
        verify(subscriptions).removeSession(sessionId);
    }

    @Test
    void cleanupIfStaleIsNoOpWhenSessionStillOpen() {
        String sessionId = UUID.randomUUID().toString();
        service.handleSessionOpened(sessionRef(sessionId));
        when(msgEndpoint.isOpen(sessionId)).thenReturn(true);

        service.cleanupIfStale(sessionId);

        verify(limitTracker, never()).releaseSession(any());
        verify(limitTracker, never()).releaseSessionSubscriptions(any());
        verify(subscriptions, never()).removeSession(sessionId);
    }

    private WebSocketSessionRef sessionRef(String sessionId) {
        return new WebSocketSessionRef(sessionId, securityUser, null, null, WebSocketSessionType.GENERAL);
    }
}
