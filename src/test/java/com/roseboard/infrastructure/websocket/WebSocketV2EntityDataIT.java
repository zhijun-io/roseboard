package com.roseboard.infrastructure.websocket;

import com.roseboard.user.UserAuthority;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.device.telemetry.TelemetryWrite;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WebSocketV2EntityDataIT extends IntegrationTestBase {
    private static final String PASSWORD = "Ws-v2-password1!";

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

    @BeforeEach
    void seed() throws Exception {
        seedTenant("ws-v2-tp-");
        deviceId = createDevice("ws-v2-device");
        deviceToken = "ws-v2-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);

        UUID adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, null, "ws-v2-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("ws-v2-admin-" + adminId + "@example.com");

        telemetryService.saveBatch(tenantId, deviceId, List.of(
                new TelemetryWrite("temperature", 26.5, Instant.now(), UUID.randomUUID())));
        await(() -> telemetryService.latest(tenantId, deviceId, "temperature") != null, 5);
    }

    @Test
    void entityDataReturnsLatestSnapshot() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ENTITY_DATA","cmdId":1,"query":{"entityFilter":{"type":"singleEntity","singleEntity":{"entityType":"DEVICE","id":"%s"}}},"latestCmd":{"keys":[{"type":"TIME_SERIES","key":"temperature"}]}}]}
                """.formatted(deviceId));

        JsonNode reply = awaitMessage(client, 5);
        assertEquals(1, reply.get("cmdId").asInt());
        assertEquals("ENTITY_DATA", reply.get("cmdUpdateType").asText());
        assertEquals(0, reply.get("errorCode").asInt());
        assertNotNull(reply.get("data"));
        JsonNode entity = reply.get("data").get("data").get(0);
        assertEquals("DEVICE", entity.get("entityId").get("entityType").asText());
        assertEquals(deviceId.toString(), entity.get("entityId").get("id").asText());
        assertTrue(entity.get("latest").get("TIME_SERIES").get("temperature").get("value").asText().contains("26.5"));
        client.close();
    }

    @Test
    void entityDataViaTelemetryPluginPathReturnsLatestSnapshot() throws Exception {
        TestWebSocketClient client = connect("/api/ws/plugins/telemetry?token=" + tenantAdminToken);
        client.send("""
                {"entityDataCmds":[{"cmdId":2,"query":{"entityFilter":{"type":"singleEntity","singleEntity":{"entityType":"DEVICE","id":"%s"}}},"latestCmd":{"keys":[{"type":"TIME_SERIES","key":"temperature"}]}}]}
                """.formatted(deviceId));

        JsonNode reply = awaitMessage(client, 5);
        assertEquals(2, reply.get("cmdId").asInt());
        assertEquals("ENTITY_DATA", reply.get("cmdUpdateType").asText());
        assertNotNull(reply.get("data").get("data"));
        client.close();
    }

    @Test
    void entityDataPushOnTelemetryWrite() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ENTITY_DATA","cmdId":3,"query":{"entityFilter":{"type":"singleEntity","singleEntity":{"entityType":"DEVICE","id":"%s"}}},"latestCmd":{"keys":[{"type":"TIME_SERIES","key":"temperature"}]}}]}
                """.formatted(deviceId));
        awaitMessage(client, 5);

        postDeviceTelemetry("{\"temperature\":31.2}");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(3, update.get("cmdId").asInt());
        assertEquals("ENTITY_DATA", update.get("cmdUpdateType").asText());
        assertNotNull(update.get("update"));
        assertTrue(update.get("update").get(0).get("latest").get("TIME_SERIES")
                .get("temperature").get("value").asText().contains("31.2"));
        client.close();
    }

    @Test
    void entityDataUnsubscribeStopsPush() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ENTITY_DATA","cmdId":4,"query":{"entityFilter":{"type":"singleEntity","singleEntity":{"entityType":"DEVICE","id":"%s"}}},"latestCmd":{"keys":[{"type":"TIME_SERIES","key":"temperature"}]}}]}
                """.formatted(deviceId));
        awaitMessage(client, 5);

        client.send("""
                {"cmds":[{"type":"ENTITY_DATA_UNSUBSCRIBE","cmdId":4}]}
                """);

        postDeviceTelemetry("{\"temperature\":99.9}");
        assertNull(client.awaitMessage(2));
        client.close();
    }

    @Test
    void entityCountReturnsInitialCountAndPushOnDeviceCreate() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ENTITY_COUNT","cmdId":5,"query":{"entityFilter":{"type":"deviceType","deviceTypes":["default"],"deviceNameFilter":"ws-v2-device"}}}]}
                """);

        JsonNode initial = awaitMessage(client, 5);
        assertEquals(5, initial.get("cmdId").asInt());
        assertEquals("COUNT_DATA", initial.get("cmdUpdateType").asText());
        assertEquals(1, initial.get("count").asInt());

        createDeviceViaApi("ws-v2-device-extra");

        JsonNode update = awaitMessage(client, 5);
        assertEquals(5, update.get("cmdId").asInt());
        assertEquals("COUNT_DATA", update.get("cmdUpdateType").asText());
        assertEquals(2, update.get("count").asInt());
        client.close();
    }

    @Test
    void entityCountUnsubscribeStopsPush() throws Exception {
        TestWebSocketClient client = connectAndAuth("/api/ws");
        client.send("""
                {"cmds":[{"type":"ENTITY_COUNT","cmdId":6,"query":{"entityFilter":{"type":"deviceType","deviceTypes":["default"],"deviceNameFilter":"ws-v2-device"}}}]}
                """);
        awaitMessage(client, 5);

        client.send("""
                {"cmds":[{"type":"ENTITY_COUNT_UNSUBSCRIBE","cmdId":6}]}
                """);

        createDeviceViaApi("ws-v2-device-unsub-extra");
        assertNull(client.awaitMessage(2));
        client.close();
    }

    private void postDeviceTelemetry(String body) throws Exception {
        var mvcResult = mockMvc.perform(post("/api/http/{token}/telemetry", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());
    }

    private void createDeviceViaApi(String name) throws Exception {
        mockMvc.perform(post("/api/devices")
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"default","tenantId":"%s"}
                                """.formatted(name, tenantId)))
                .andExpect(status().isOk());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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
        user.setLastName("V2");
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
