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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WebSocketClusterPushIT extends IntegrationTestBase {
    private static final String PASSWORD = "Ws-cluster-password1!";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("server.ws.auth_timeout_ms", () -> 2000);
        registry.add("server.ws.cluster.enabled", () -> true);
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
        seedTenant("ws-cluster-tp-");
        deviceId = createDevice("ws-cluster-device");
        deviceToken = "ws-cluster-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);

        UUID adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, null, "ws-cluster-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("ws-cluster-admin-" + adminId + "@example.com");

        telemetryService.saveBatch(tenantId, deviceId, List.of(
                new TelemetryWrite("temperature", 20.0, Instant.now(), UUID.randomUUID())));
        await(() -> telemetryService.latest(tenantId, deviceId, "temperature") != null, 5);
    }

    @Test
    void clusterModeDeliversTelemetryPushViaRedisBroadcast() throws Exception {
        TestWebSocketClient client = connect("/api/ws?token=" + tenantAdminToken);
        client.send("""
                {"cmds":[{"type":"TIMESERIES","cmdId":8,"entityType":"DEVICE","entityId":"%s","keys":"temperature"}]}
                """.formatted(deviceId));
        awaitMessage(client, 5);

        postDeviceTelemetry("{\"temperature\":33.3}");

        JsonNode update = awaitMessage(client, 5);
        assertNotNull(update.get("data"));
        assertTrue(update.get("data").get("temperature").get(0).get(1).asText().contains("33.3"));
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

    private static JsonNode awaitMessage(TestWebSocketClient client, int seconds) throws Exception {
        String payload = client.awaitMessage(seconds);
        assertNotNull(payload, "expected websocket message");
        return new ObjectMapper().readTree(payload);
    }

    private TestWebSocketClient connect(String path) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(client, null, URI.create("ws://localhost:" + port + path)).get(5, TimeUnit.SECONDS);
        await(() -> client.isOpen(), 3);
        return client;
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
        user.setLastName("Cluster");
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
