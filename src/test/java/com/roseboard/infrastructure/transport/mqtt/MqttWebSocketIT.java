package com.roseboard.infrastructure.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MqttWebSocketIT extends IntegrationTestBase {
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
        registry.add("roseboard.transport.mqtt.web-socket.enabled", () -> "true");
        registry.add("roseboard.transport.mqtt.web-socket.port", () -> "0");
        registry.add("roseboard.transport.mqtt.web-socket.path", () -> "/mqtt");
    }

    @Autowired MqttTransportServer mqttServer;
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired TelemetryService telemetryService;

    private UUID deviceId;
    private String deviceToken;
    private Mqtt3AsyncClient client;

    @BeforeEach
    void seed() {
        seedTenant("mqtt-ws-tp-");
        deviceId = createDevice("mqtt-ws-device");
        deviceToken = "mqtt-ws-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @AfterEach
    void disconnect() {
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Test
    void accessTokenConnectOverWebSocket() throws Exception {
        client = MqttClient.builder()
                .identifier("mqtt-ws-it-" + UUID.randomUUID())
                .serverHost("127.0.0.1")
                .serverPort(mqttServer.boundWebSocketPort())
                .useMqttVersion3()
                .webSocketConfig()
                .serverPath("/mqtt")
                .subprotocol("mqtt")
                .applyWebSocketConfig()
                .buildAsync();

        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, client.connectWith()
                .simpleAuth()
                .username(deviceToken)
                .applySimpleAuth()
                .send()
                .join()
                .getReturnCode());

        client.publishWith()
                .topic(MqttTopics.Device.TELEMETRY)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"temp\":23}".getBytes(StandardCharsets.UTF_8))
                .send()
                .join();

        await(() -> telemetryService.latest(tenantId, deviceId, "temp") != null, 5);
        assertEquals(23, ((Number) telemetryService.latest(tenantId, deviceId, "temp").value()).intValue());
    }
}
