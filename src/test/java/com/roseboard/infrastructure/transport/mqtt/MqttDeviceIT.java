package com.roseboard.infrastructure.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MqttDeviceIT extends IntegrationTestBase {
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
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired TelemetryService telemetryService;
    @Autowired DeviceAttributeService attributeService;

    private UUID deviceId;
    private String deviceToken;
    private Mqtt3AsyncClient client;

    @BeforeEach
    void seed() {
        seedTenant("mqtt-device-tp-");
        deviceId = createDevice("mqtt-device");
        deviceToken = "mqtt-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @AfterEach
    void disconnect() {
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Test
    void accessTokenConnectAndTelemetry() throws Exception {
        connect(deviceToken);
        client.publishWith()
                .topic(MqttTopics.Device.TELEMETRY)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"temp\":21}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        await(() -> telemetryService.latest(tenantId, deviceId, "temp") != null, 5);
        assertEquals(21, ((Number) telemetryService.latest(tenantId, deviceId, "temp").value()).intValue());
    }

    @Test
    void invalidTokenRejected() {
        var exception = assertThrows(Exception.class, () -> connectRaw("bad-token"));
        assertTrue(exception instanceof Mqtt3ConnAckException
                || exception.getCause() instanceof Mqtt3ConnAckException);
    }


    @Test
    void attributesPublishAndReadRoundTrip() throws Exception {
        connect(deviceToken);
        client.publishWith()
                .topic(MqttTopics.Device.ATTRIBUTES)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"color\":\"blue\"}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);
        await(() -> {
            var principal = new DeviceCredentialService.DevicePrincipal(deviceId, tenantId, null, "ACCESS_TOKEN");
            return !attributeService.readFromDevice(
                    principal, AttributeScope.CLIENT, List.of(new AttributeKey("color"))).isEmpty();
        }, 5);

        CompletableFuture<Mqtt3Publish> responseFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, responseFuture::complete);
        client.subscribeWith()
                .topicFilter(MqttTopics.Device.ATTRIBUTES_RESPONSE_FILTER)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        client.publishWith()
                .topic(MqttTopics.Device.ATTRIBUTES_REQUEST_PREFIX + "7")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"clientKeys\":\"color\"}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        String response = new String(responseFuture.get(5, TimeUnit.SECONDS).getPayloadAsBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("blue"));
    }

    private void connect(String token) {
        Mqtt3ConnAck ack = connectRaw(token);
        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, ack.getReturnCode());
        assertTrue(client.getState().isConnected());
    }

    private Mqtt3ConnAck connectRaw(String token) {
        int port = mqttServer.boundPort();
        client = MqttClient.builder()
                .identifier("mqtt-it-" + UUID.randomUUID())
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
        return ack;
    }
}
