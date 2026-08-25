package com.roseboard.infrastructure.transport.mqtt;

import com.roseboard.user.UserAuthority;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.support.IntegrationTestBase;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MqttDeviceRpcIT extends IntegrationTestBase {
    private static final String PASSWORD = "Mqtt-rpc-password1!";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("roseboard.transport.mqtt.enabled", () -> "true");
        registry.add("roseboard.transport.mqtt.port", () -> "0");
    }

    @Autowired MqttTransportServer mqttServer;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired
    DeviceCredentialService credentialsService;

    private UUID deviceId;
    private String deviceToken;
    private String tenantAdminToken;
    private Mqtt3AsyncClient client;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("mqtt-rpc-tp-");
        deviceId = createDevice("mqtt-rpc-device");
        deviceToken = "mqtt-rpc-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
        UUID adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, "mqtt-rpc-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("mqtt-rpc-admin-" + adminId + "@example.com");
    }

    @AfterEach
    void disconnect() {
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Test
    void twowayDeliversOverMqttAndReturnsReply() throws Exception {
        connect(deviceToken);
        CompletableFuture<Mqtt3Publish> rpcFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, publish -> {
            if (publish.getTopic().toString().startsWith(MqttTopics.Device.RPC_REQUEST_PREFIX)) {
                rpcFuture.complete(publish);
            }
        });
        client.subscribeWith()
                .topicFilter(MqttTopics.Device.RPC_REQUEST_FILTER)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        MvcResult adminStarted = mockMvc.perform(post("/api/rpc/twoway/{deviceId}", deviceId)
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"setGpio","params":{"pin":7},"persistent":false,"timeout":10000}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        Mqtt3Publish rpcPublish = rpcFuture.get(5, TimeUnit.SECONDS);
        String rpcBody = new String(rpcPublish.getPayloadAsBytes(), StandardCharsets.UTF_8);
        assertTrue(rpcBody.contains("\"id\":1"));
        assertTrue(rpcBody.contains("\"method\":\"setGpio\""));

        String topic = rpcPublish.getTopic().toString();
        int requestId = Integer.parseInt(topic.substring(MqttTopics.Device.RPC_REQUEST_PREFIX.length()));
        client.publishWith()
                .topic(MqttTopics.Device.RPC_RESPONSE_PREFIX + requestId)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        mockMvc.perform(asyncDispatch(adminStarted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        assertTrue(client.getState().isConnected());
    }

    @Test
    void deviceToServerRpcRoundTrip() throws Exception {
        connect(deviceToken);
        CompletableFuture<Mqtt3Publish> responseFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, responseFuture::complete);
        client.subscribeWith()
                .topicFilter(MqttTopics.Device.RPC_RESPONSE_FILTER)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        client.publishWith()
                .topic(MqttTopics.Device.RPC_REQUEST_PREFIX + "42")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"method\":\"sumOnServer\",\"params\":{\"a\":2,\"b\":2}}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        String response = new String(responseFuture.get(5, TimeUnit.SECONDS).getPayloadAsBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("\"result\":4"));
    }

    private void connect(String token) {
        int port = mqttServer.boundPort();
        client = MqttClient.builder()
                .identifier("mqtt-rpc-it-" + UUID.randomUUID())
                .serverHost("127.0.0.1")
                .serverPort(port)
                .useMqttVersion3()
                .buildAsync();
        Mqtt3ConnAck ack = client.connectWith()
                .simpleAuth()
                .username(token)
                .applySimpleAuth()
                .send()
                .join();
        assertNotNull(ack);
        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, ack.getReturnCode());
    }

    private void insertUser(UUID id, UUID tenantId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setAuthority(authority);
        user.setVersion(1L);
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
