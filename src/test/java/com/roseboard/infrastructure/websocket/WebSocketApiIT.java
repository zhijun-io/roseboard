package com.roseboard.infrastructure.websocket;

import com.roseboard.user.UserAuthority;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.telemetry.TelemetryService;
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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WebSocketApiIT extends IntegrationTestBase {
    private static final String PASSWORD = "Ws-api-password1!";

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
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired TelemetryService telemetryService;

    private UUID deviceId;
    private String deviceToken;
    private String tenantAdminToken;
    private UUID tenantAdminId;
    private UUID otherTenantDeviceId;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("ws-api-tp-");
        deviceId = createDevice("ws-api-device");
        deviceToken = "ws-api-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);

        UUID adminId = UUID.randomUUID();
        tenantAdminId = adminId;
        insertUser(adminId, tenantId, null, "ws-api-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("ws-api-admin-" + adminId + "@example.com");

        UUID mainTenantId = tenantId;
        seedTenant("ws-api-other-tp-");
        otherTenantDeviceId = createDevice("ws-api-other-device");
        tenantId = mainTenantId;
    }

    @Test
    void authWithValidJwtKeepsConnectionOpen() throws Exception {
        TestWebSocketClient client = connect("/api/ws");
        client.send("""
                {"authCmd":{"cmdId":0,"token":"%s"},"cmds":[]}
                """.formatted(tenantAdminToken));

        await(() -> client.isOpen(), 3);
        assertTrue(client.isOpen());
        client.close();
    }

    @Test
    void authWithValidApiKeyKeepsConnectionOpen() throws Exception {
        String apiKey = createApiKeyForUser(tenantAdminId);
        TestWebSocketClient client = connect("/api/ws");
        client.send("""
                {"authCmd":{"cmdId":0,"apiKey":"%s"},"cmds":[]}
                """.formatted(apiKey));

        await(() -> client.isOpen(), 3);
        assertTrue(client.isOpen());
        client.close();
    }

    @Test
    void authWithValidApiKeyCanSubscribeTimeseries() throws Exception {
        String apiKey = createApiKeyForUser(tenantAdminId);
        TestWebSocketClient client = connect("/api/ws");
        client.send("""
                {"authCmd":{"cmdId":0,"apiKey":"%s"},"cmds":[{"type":"TIMESERIES","cmdId":12,"entityType":"DEVICE","entityId":"%s","keys":"pressure"}]}
                """.formatted(apiKey, deviceId));

        JsonNode initial = awaitMessage(client, 5);
        assertEquals(12, initial.get("subscriptionId").asInt());
        assertNotNull(initial.get("data"));
        client.close();
    }

    @Test
    void invalidApiKeyClosesConnection() throws Exception {
        TestWebSocketClient client = connect("/api/ws");
        client.send("""
                {"authCmd":{"cmdId":0,"apiKey":"invalid-api-key"},"cmds":[]}
                """);

        await(() -> {
            try {
                return client.awaitClose(2500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, 3);
        assertFalse(client.isOpen());
    }

    @Test
    void invalidJwtClosesConnection() throws Exception {
        TestWebSocketClient client = connect("/api/ws");
        client.send("""
                {"authCmd":{"cmdId":0,"token":"invalid-token"},"cmds":[]}
                """);

        await(() -> {
            try {
                return client.awaitClose(2500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, 3);
        assertFalse(client.isOpen());
    }

    @Test
    void authTimeoutClosesConnection() throws Exception {
        TestWebSocketClient client = connect("/api/ws");
        await(() -> {
            try {
                return client.awaitClose(3500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, 5);
        assertFalse(client.isOpen());
    }

    @Test
    void timeseriesSubscriptionReceivesPushOnTelemetryWrite() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":7,"entityType":"DEVICE","entityId":"%s","keys":"temperature"}]}
                """.formatted(deviceId));

        JsonNode initial = awaitMessage(client, 5);
        assertEquals(7, initial.get("subscriptionId").asInt());
        assertNotNull(initial.get("data"));

        postDeviceTelemetry("{\"temperature\":33.3}");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(7, update.get("subscriptionId").asInt());
        assertTrue(update.get("data").get("temperature").toString().contains("33.3"));
        client.close();
    }

    @Test
    void attributesSubscriptionReceivesPushOnAttributeChange() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ATTRIBUTES","cmdId":8,"entityType":"DEVICE","entityId":"%s","scope":"CLIENT_SCOPE","keys":"mode"}]}
                """.formatted(deviceId));

        awaitMessage(client, 5);

        postDeviceAttributes("{\"mode\":\"auto\"}");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(8, update.get("subscriptionId").asInt());
        assertTrue(update.get("data").get("mode").toString().contains("auto"));
        client.close();
    }

    @Test
    void unauthorizedDeviceSubscriptionReturnsErrorUpdate() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":9,"entityType":"DEVICE","entityId":"%s","keys":"temperature"}]}
                """.formatted(otherTenantDeviceId));

        JsonNode error = awaitMessage(client, 5);
        assertEquals(9, error.get("subscriptionId").asInt());
        assertEquals(2, error.get("errorCode").asInt());
        client.close();
    }

    @Test
    void timeseriesHistoryReturnsPoints() throws Exception {
        postDeviceTelemetry("{\"temperature\":11.1}");
        await(() -> telemetryService.latest(tenantId, deviceId, "temperature") != null, 5);

        long endTs = System.currentTimeMillis() + 1000;
        long startTs = endTs - 60_000;

        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"TIMESERIES_HISTORY","cmdId":10,"entityType":"DEVICE","entityId":"%s","keys":"temperature","startTs":%d,"endTs":%d,"limit":10}]}
                """.formatted(deviceId, startTs, endTs));

        JsonNode reply = awaitMessage(client, 5);
        assertEquals(10, reply.get("subscriptionId").asInt());
        assertTrue(reply.get("data").get("temperature").isArray());
        assertFalse(reply.get("data").get("temperature").isEmpty());
        client.close();
    }

    @Test
    void telemetryPluginPathWithQueryTokenWorks() throws Exception {
        TestWebSocketClient client = connect("/api/ws/plugins/telemetry?token=" + tenantAdminToken);
        client.send("""
                {"tsSubCmds":[{"type":"TIMESERIES","cmdId":11,"entityType":"DEVICE","entityId":"%s","keys":"humidity"}]}
                """.formatted(deviceId));

        JsonNode initial = awaitMessage(client, 5);
        assertEquals(11, initial.get("subscriptionId").asInt());

        postDeviceTelemetry("{\"humidity\":55}");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(11, update.get("subscriptionId").asInt());
        assertTrue(update.get("data").get("humidity").toString().contains("55"));
        client.close();
    }

    private TestWebSocketClient connectAndAuth(String path) throws Exception {
        TestWebSocketClient client = connect(path);
        client.send("""
                {"authCmd":{"cmdId":0,"token":"%s"},"cmds":[]}
                """.formatted(tenantAdminToken));
        await(() -> client.isOpen(), 3);
        return client;
    }

    private TestWebSocketClient connect(String path) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(client, null, URI.create("ws://localhost:" + port + path)).get(5, TimeUnit.SECONDS);
        await(() -> client.isOpen(), 3);
        return client;
    }

    private void postDeviceTelemetry(String body) throws Exception {
        var mvcResult = mockMvc.perform(post("/api/http/{token}/telemetry", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());
    }

    private void postDeviceAttributes(String body) throws Exception {
        var mvcResult = mockMvc.perform(post("/api/http/{token}/attributes", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());
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
        user.setLastName("Admin");
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(id);
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        userCredentialMapper.insert(credentials);
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        String response = mockMvc.perform(post("/api/users/" + userId + "/api-keys")
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","description":"ws-api-key","enabled":true}
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("value").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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
        private volatile boolean closed;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.offer(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }

        void send(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        boolean isOpen() {
            return session != null && session.isOpen();
        }

        void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }

        boolean awaitClose(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                if (closed || session == null || !session.isOpen()) {
                    return true;
                }
                Thread.sleep(50);
            }
            return closed || session == null || !session.isOpen();
        }

        String awaitMessage(int seconds) throws InterruptedException {
            return messages.poll(seconds, TimeUnit.SECONDS);
        }
    }
}
