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

import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MqttTlsIT extends IntegrationTestBase {
    private static final String KEYSTORE_PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    private static final MqttTestSslSupport.Material sslMaterial;

    static {
        try {
            sslMaterial = MqttTestSslSupport.create(java.nio.file.Files.createTempDirectory("mqtt-tls-it"));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("roseboard.transport.mqtt.enabled", () -> "true");
        registry.add("roseboard.transport.mqtt.port", () -> "0");
        registry.add("roseboard.transport.mqtt.ssl.enabled", () -> "true");
        registry.add("roseboard.transport.mqtt.ssl.port", () -> "0");
        registry.add("roseboard.transport.mqtt.ssl.key-store", () -> sslMaterial.serverKeyStore().toString());
        registry.add("roseboard.transport.mqtt.ssl.key-store-password", () -> KEYSTORE_PASSWORD);
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
        seedTenant("mqtt-tls-tp-");
        deviceId = createDevice("mqtt-tls-device");
        deviceToken = "mqtt-tls-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @AfterEach
    void disconnect() {
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Test
    void accessTokenConnectOverTls() throws Exception {
        connectWithOptionalClientCert(false, deviceToken);
        client.publishWith()
                .topic(MqttTopics.Device.TELEMETRY)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"temp\":22}".getBytes(StandardCharsets.UTF_8))
                .send()
                .join();
        await(() -> telemetryService.latest(tenantId, deviceId, "temp") != null, 5);
        assertEquals(22, ((Number) telemetryService.latest(tenantId, deviceId, "temp").value()).intValue());
    }

    private void connectWithOptionalClientCert(boolean ignored, String token) throws Exception {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = java.nio.file.Files.newInputStream(sslMaterial.serverKeyStore())) {
            trustKeyStore.load(input, KEYSTORE_PASSWORD.toCharArray());
        }
        trustManagerFactory.init(trustKeyStore);

        client = MqttClient.builder()
                .identifier("mqtt-tls-it-" + UUID.randomUUID())
                .serverHost("127.0.0.1")
                .serverPort(mqttServer.boundSslPort())
                .useMqttVersion3()
                .sslConfig()
                .trustManagerFactory(trustManagerFactory)
                .hostnameVerifier((hostname, session) -> true)
                .applySslConfig()
                .buildAsync();

        var connectBuilder = client.connectWith();
        if (token != null) {
            connectBuilder = connectBuilder.simpleAuth().username(token).applySimpleAuth();
        }
        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, connectBuilder.send().join().getReturnCode());
    }
}
