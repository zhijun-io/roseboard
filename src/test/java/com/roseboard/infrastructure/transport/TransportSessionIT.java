package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialService.DevicePrincipal;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportSessionIT extends TransportIntegrationTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.transport.mqtt.enabled", () -> "false");
    }

    @Autowired TransportSessionRegistry sessions;
    @Autowired
    DeviceCredentialService credentialsService;

    private UUID deviceId;
    private DevicePrincipal principal;

    @BeforeEach
    void seed() {
        seedTenant("transport-session-tp-");
        deviceId = createDevice("session-device");
        String token = "session-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, token, null);
        principal = credentialsService.authenticateAccessToken(token);
    }

    @Test
    void asyncSessionDeliversWithoutClosing() {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        SessionInfo session = sessions.registerAsync(
                SessionInfo.fromPrincipal(principal), received::set, Duration.ofSeconds(30));
        sessions.setSubscribedToRpc(session.sessionId(), true);
        TransportToDevicePayload downlink = downlink();

        assertEquals(DeliverResult.DELIVERED, sessions.deliver(deviceId, downlink));
        assertEquals(downlink, received.get());
        assertEquals(DeliverResult.DELIVERED, sessions.deliver(deviceId, downlink()));
        assertEquals(1, sessions.nextRequestId(session.sessionId()));
    }

    @Test
    void syncSessionDeliversOnceThenCloses() {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        SessionInfo session = sessions.register(SessionInfo.fromPrincipal(principal), received::set, Duration.ofSeconds(30));
        sessions.setSubscribedToRpc(session.sessionId(), true);
        var params = JacksonUtils.newObjectNode();
        params.put("method", "ping");
        TransportToDevicePayload downlink = new TransportToDevicePayload("ping", params, 1);

        assertEquals(DeliverResult.DELIVERED, sessions.deliver(deviceId, downlink));
        assertEquals(downlink, received.get());
        assertEquals(DeliverResult.NO_SESSION, sessions.deliver(deviceId, downlink));
        assertThrows(IllegalStateException.class, () -> sessions.nextRequestId(session.sessionId()));
    }

    @Test
    void syncSessionTimeoutNotifiesListener() throws InterruptedException {
        AtomicReference<SessionCloseNotification> closed = new AtomicReference<>();
        sessions.register(SessionInfo.fromPrincipal(principal), listener(null, closed), Duration.ofMillis(120));

        await(() -> closed.get() != null, 5);
        assertEquals("session timeout!", closed.get().message());
    }

    @Test
    void recordActivityUpdatesLastActivityAt() throws InterruptedException {
        SessionInfo session = sessions.register(SessionInfo.fromPrincipal(principal), message -> {}, Duration.ofSeconds(30));
        Thread.sleep(20);
        sessions.recordActivity(session.sessionId());

        SessionInfo updated = sessions.snapshot(session.sessionId());
        assertTrue(updated.lastActivityAt() >= session.lastActivityAt());
    }

    @Test
    void closeSessionPreventsFurtherDelivery() {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        SessionInfo session = sessions.register(SessionInfo.fromPrincipal(principal), received::set, Duration.ofSeconds(30));
        sessions.closeSession(session.sessionId());

        assertEquals(DeliverResult.SESSION_CLOSED, sessions.deliver(deviceId, downlink()));
        assertNull(received.get());
    }

    @Test
    void sessionTimeoutPreventsFurtherDelivery() throws InterruptedException {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        AtomicReference<SessionCloseNotification> closed = new AtomicReference<>();
        sessions.register(SessionInfo.fromPrincipal(principal), listener(received, closed), Duration.ofMillis(120));
        await(() -> closed.get() != null, 5);

        assertEquals(DeliverResult.SESSION_CLOSED, sessions.deliver(deviceId, downlink()));
        assertNull(received.get());
    }

    @Test
    void deliverWithoutSessionReturnsNoSession() {
        assertEquals(DeliverResult.NO_SESSION, sessions.deliver(deviceId, downlink()));
    }

    @Test
    void requestIdIncrementsAndRpcAssociationResolves() {
        SessionInfo session = sessions.register(SessionInfo.fromPrincipal(principal), message -> {}, Duration.ofSeconds(30));
        UUID rpcId = UUID.randomUUID();

        assertEquals(1, sessions.nextRequestId(session.sessionId()));
        assertEquals(2, sessions.nextRequestId(session.sessionId()));
        sessions.associateRpc(session.sessionId(), 1, rpcId);

        assertEquals(rpcId, sessions.resolveRpcId(session.sessionId(), 1).orElseThrow());
        assertTrue(sessions.resolveRpcId(session.sessionId(), 99).isEmpty());
    }

    @Test
    void newSessionReplacesPreviousSessionForSameDevice() {
        AtomicReference<TransportToDevicePayload> first = new AtomicReference<>();
        AtomicReference<TransportToDevicePayload> second = new AtomicReference<>();
        sessions.register(SessionInfo.fromPrincipal(principal), first::set, Duration.ofSeconds(30));
        sessions.register(SessionInfo.fromPrincipal(principal), second::set, Duration.ofSeconds(30));
        sessions.setSubscribedToRpc(sessions.activeSessionId(deviceId).orElseThrow(), true);

        TransportToDevicePayload downlink = downlink();
        sessions.deliver(deviceId, downlink);

        assertNull(first.get());
        assertEquals(downlink, second.get());
        assertEquals(DeliverResult.NO_SESSION, sessions.deliver(deviceId, downlink));
    }

    @Test
    void closedSessionRejectsNextRequestId() {
        SessionInfo session = sessions.register(SessionInfo.fromPrincipal(principal), message -> {}, Duration.ofSeconds(30));
        sessions.closeSession(session.sessionId());
        assertThrows(IllegalStateException.class, () -> sessions.nextRequestId(session.sessionId()));
    }
}
