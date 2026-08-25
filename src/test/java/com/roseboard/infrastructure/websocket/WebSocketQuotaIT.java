package com.roseboard.infrastructure.websocket;

import com.roseboard.user.UserAuthority;
import com.roseboard.common.JacksonUtils;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.support.IntegrationTestBase;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
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
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WebSocketQuotaIT extends IntegrationTestBase {
    private static final String PASSWORD = "Ws-quota-password1!";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("server.ws.auth_timeout_ms", () -> 2000);
        registry.add("server.ws.updates_rate_limit", () -> "1:3600");
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
    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired CustomerMapper customerMapper;

    private UUID deviceId;
    private String deviceToken;
    private String tenantAdminToken;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("ws-quota-tp-");
        deviceId = createDevice("ws-quota-device");
        deviceToken = "ws-quota-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);

        UUID adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, null, "ws-quota-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("ws-quota-admin-" + adminId + "@example.com");
    }

    @Test
    void secondConnectionClosedWhenTenantSessionLimitReached() throws Exception {
        setProfileLimit(TenantProfileWsLimits.MAX_WS_SESSIONS_PER_TENANT, 1);

        TestWebSocketClient first = connect("/api/ws?token=" + tenantAdminToken);
        assertTrue(first.isOpen());

        TestWebSocketClient second = connectExpectClose("/api/ws?token=" + tenantAdminToken);
        assertTrue(second.closeReason().contains("Max tenant sessions limit reached")
                || second.closeCode() == CloseStatus.POLICY_VIOLATION.getCode());

        first.close();
    }

    @Test
    void telemetryPushReturnsTooManyUpdatesWhenRateLimitExceeded() throws Exception {
        TestWebSocketClient client = connect("/api/ws?token=" + tenantAdminToken);
        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":7,"entityType":"DEVICE","entityId":"%s","keys":"temperature"}]}
                """.formatted(deviceId));

        JsonNode snapshot = awaitMessage(client, 5);
        assertEquals(7, snapshot.get("subscriptionId").asInt());
        assertEquals(0, snapshot.path("errorCode").asInt(0));

        postDeviceTelemetry("{\"temperature\":42.0}");

        JsonNode rateLimited = awaitMessage(client, 5);
        assertEquals(7, rateLimited.get("subscriptionId").asInt());
        assertEquals(SubscriptionErrorCode.TOO_MANY_UPDATES.getCode(), rateLimited.get("errorCode").asInt());
        client.close();
    }

    @Test
    void secondSubscriptionClosedWhenTenantSubscriptionLimitReached() throws Exception {
        setProfileLimit(TenantProfileWsLimits.MAX_WS_SUBSCRIPTIONS_PER_TENANT, 1);

        TestWebSocketClient client = connect("/api/ws?token=" + tenantAdminToken);
        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":1,"entityType":"DEVICE","entityId":"%s","keys":"temperature"}]}
                """.formatted(deviceId));
        awaitMessage(client, 5);

        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":2,"entityType":"DEVICE","entityId":"%s","keys":"humidity"}]}
                """.formatted(deviceId));
        await(() -> client.closeCode() != null || !client.isOpen(), 5);
        assertTrue(client.closeReason() != null && client.closeReason().contains("Max tenant subscriptions limit reached")
                || client.closeCode() == CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void secondPublicUserConnectionClosedWhenPublicSessionLimitReached() throws Exception {
        UUID customerId = UUID.randomUUID();
        insertCustomer(customerId);
        UUID publicUserId = UUID.randomUUID();
        insertUser(publicUserId, tenantId, customerId, "ws-quota-public-" + publicUserId + "@example.com", UserAuthority.CUSTOMER_USER);
        String publicToken = publicLogin("ws-quota-public-" + publicUserId + "@example.com");

        setProfileLimit(TenantProfileWsLimits.MAX_WS_SESSIONS_PER_PUBLIC_USER, 1);

        TestWebSocketClient first = connect("/api/ws?token=" + publicToken);
        assertTrue(first.isOpen());

        TestWebSocketClient second = connectExpectClose("/api/ws?token=" + publicToken);
        assertTrue(second.closeReason().contains("Max public user sessions limit reached")
                || second.closeCode() == CloseStatus.POLICY_VIOLATION.getCode());

        first.close();
    }

    private void insertCustomer(UUID customerId) {
        com.roseboard.customer.CustomerEntity customer = new com.roseboard.customer.CustomerEntity();
        customer.setId(customerId);
        customer.setCreatedTime(System.currentTimeMillis());
        customer.setTenantId(tenantId);
        customer.setTitle("ws-quota-public-customer");
        customerMapper.insert(customer);
    }

    private String publicLogin(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login/public")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private void setProfileLimit(String key, long value) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        TenantProfileEntity profile = tenantProfileMapper.selectById(tenant.getTenantProfileId());
        ObjectNode root = (ObjectNode) profile.getProfileData().deepCopy();
        ObjectNode configuration = root.has("configuration")
                ? (ObjectNode) root.get("configuration")
                : JacksonUtils.objectMapper().createObjectNode();
        configuration.put(key, value);
        root.set("configuration", configuration);
        profile.setProfileData(root);
        tenantProfileMapper.updateById(profile);
    }

    private void postDeviceTelemetry(String body) throws Exception {
        var mvcResult = mockMvc.perform(post("/api/http/{token}/telemetry", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());
    }

    private TestWebSocketClient connect(String path) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(client, null, URI.create("ws://localhost:" + port + path)).get(5, TimeUnit.SECONDS);
        await(() -> client.isOpen(), 3);
        return client;
    }

    private TestWebSocketClient connectExpectClose(String path) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(client, null, URI.create("ws://localhost:" + port + path)).get(5, TimeUnit.SECONDS);
        await(() -> client.closeCode() != null || !client.isOpen(), 5);
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
        user.setLastName("Quota");
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
        private final AtomicReference<Integer> closeCode = new AtomicReference<>();
        private final AtomicReference<String> closeReason = new AtomicReference<>();
        private volatile WebSocketSession session;

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
            closeCode.set(status.getCode());
            closeReason.set(status.getReason());
        }

        void send(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        boolean isOpen() {
            return session != null && session.isOpen();
        }

        Integer closeCode() {
            return closeCode.get();
        }

        String closeReason() {
            return closeReason.get();
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
