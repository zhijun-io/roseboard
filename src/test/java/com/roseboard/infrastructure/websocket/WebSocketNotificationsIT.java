package com.roseboard.infrastructure.websocket;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyOptions;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.notification.NotificationEntity;
import com.roseboard.notification.NotificationMapper;
import com.roseboard.support.IntegrationTestBase;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WebSocketNotificationsIT extends IntegrationTestBase {
    private static final String PASSWORD = "Ws-notif-password1!";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("server.ws.auth_timeout_ms", () -> 2000);
    }

    @LocalServerPort
    private int port;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired NotificationCenter notificationCenter;
    @Autowired NotificationMapper notificationMapper;

    private UUID adminId;
    private String adminToken;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("ws-notif-tp-");
        adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, null, "ws-notif-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        adminToken = login("ws-notif-admin-" + adminId + "@example.com");
        notificationMapper.delete(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, adminId));
    }

    @Test
    void notificationsPushOnNewInboxMessage() throws Exception {
        TestWebSocketClient client = connect("/api/ws/plugins/notifications?token=" + adminToken);
        client.send("""
                {"unreadSubCmd":{"cmdId":1,"limit":10}}
                """);

        JsonNode snapshot = awaitMessage(client, 5);
        assertEquals(1, snapshot.get("cmdId").asInt());
        assertEquals("NOTIFICATIONS", snapshot.get("cmdUpdateType").asText());
        assertEquals(0, snapshot.get("errorCode").asInt());
        assertEquals(0, snapshot.get("totalUnreadCount").asInt());

        notifyAdmin("USAGE_WARNING");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(1, update.get("cmdId").asInt());
        assertEquals("NOTIFICATIONS", update.get("cmdUpdateType").asText());
        assertNotNull(update.get("update"));
        assertEquals("USAGE_WARNING", update.get("update").get("type").asText());
        assertEquals(1, update.get("totalUnreadCount").asInt());
        client.close();
    }

    @Test
    void markReadUpdatesCountSubscription() throws Exception {
        notifyAdmin("USAGE_WARNING");
        notifyAdmin("USAGE_EXCEEDED");

        TestWebSocketClient client = connect("/api/ws/plugins/notifications?token=" + adminToken);
        client.send("""
                {"unreadCountSubCmd":{"cmdId":2}}
                """);

        JsonNode initial = awaitMessage(client, 5);
        assertEquals(2, initial.get("cmdId").asInt());
        assertEquals("NOTIFICATIONS_COUNT", initial.get("cmdUpdateType").asText());
        assertEquals(2, initial.get("totalUnreadCount").asInt());

        UUID notificationId = notificationMapper.selectList(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, adminId)
                .orderByAsc(NotificationEntity::getCreatedTime)).getFirst().getId();

        client.send("""
                {"markAsReadCmd":{"cmdId":3,"notifications":["%s"]}}
                """.formatted(notificationId));

        JsonNode countUpdate = awaitMessage(client, 5);
        assertEquals(2, countUpdate.get("cmdId").asInt());
        assertEquals("NOTIFICATIONS_COUNT", countUpdate.get("cmdUpdateType").asText());
        assertEquals(1, countUpdate.get("totalUnreadCount").asInt());

        NotificationEntity entity = notificationMapper.selectById(notificationId);
        assertEquals("READ", entity.getStatus());
        client.close();
    }

    private void notifyAdmin(String notificationType) {
        notificationCenter.notify(tenantId, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(adminId, "ws-notif-admin-" + adminId + "@example.com", null)),
                notificationType,
                "usage.threshold",
                Map.of("status", notificationType.replace("USAGE_", ""), "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));
    }

    private TestWebSocketClient connect(String path) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(client, null, URI.create("ws://localhost:" + port + path)).get(5, TimeUnit.SECONDS);
        await(() -> client.isOpen(), 3);
        return client;
    }

    private static JsonNode awaitMessage(TestWebSocketClient client, int seconds) throws Exception {
        String payload = client.awaitMessage(seconds);
        assertNotNull(payload, "expected websocket message");
        return new ObjectMapper().readTree(payload);
    }

    private void insertUser(UUID id, UUID tenant, UUID customer, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenant);
        user.setCustomerId(customer);
        user.setEmail(email);
        user.setAuthority(authority);
        user.setFirstName("Ws");
        user.setLastName("Notif");
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(id);
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        userCredentialMapper.insert(credentials);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private static final class TestWebSocketClient extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private volatile WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.offer(message.getPayload());
        }

        void send(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        boolean isOpen() {
            return session != null && session.isOpen();
        }

        void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }

        String awaitMessage(int seconds) throws InterruptedException {
            return messages.poll(seconds, TimeUnit.SECONDS);
        }
    }
}
