package com.roseboard.infrastructure.transport.mqtt;

import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.infrastructure.transport.X509CertificateUtil;
import com.roseboard.support.IntegrationTestBase;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttVersion;
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

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MqttTransportHandlerX509IT extends IntegrationTestBase {
    private static final String KEYSTORE_PASSWORD = "password";
    private static final MqttTestSslSupport.Material sslMaterial;

    static {
        try {
            sslMaterial = MqttTestSslSupport.create(java.nio.file.Files.createTempDirectory("mqtt-x509-handler-it"));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.transport.mqtt.enabled", () -> "true");
        registry.add("roseboard.transport.mqtt.port", () -> "0");
    }

    @Autowired MqttTransportHandler handler;
    @Autowired
    DeviceCredentialService credentialsService;

    private UUID deviceId;
    private X509Certificate clientCertificate;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("mqtt-x509-handler-tp-");
        deviceId = createDevice("mqtt-x509-handler-device");
        KeyStore clientKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = java.nio.file.Files.newInputStream(sslMaterial.clientKeyStore())) {
            clientKeyStore.load(input, KEYSTORE_PASSWORD.toCharArray());
        }
        clientCertificate = (X509Certificate) clientKeyStore.getCertificate("client");
        String hash = X509CertificateUtil.sha3HashHex(clientCertificate);
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.X509_CERTIFICATE, hash,
                "{\"fingerprint\":\"" + hash + "\"}");
        assertNotNull(credentialsService.authenticateX509Certificate(hash));
    }

    @Test
    void acceptsConnectWhenPeerCertificateMatchesCredentials() {
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.attr(MqttSslPeerCertificateHandler.PEER_CERTIFICATE).set(clientCertificate);

        channel.writeInbound(connectMessage("x509-device-" + deviceId));
        MqttConnAckMessage ack = channel.readOutbound();

        assertNotNull(ack);
        assertEquals(MqttConnectReturnCode.CONNECTION_ACCEPTED, ack.variableHeader().connectReturnCode());
    }

    private static MqttConnectMessage connectMessage(String clientId) {
        MqttFixedHeader header = new MqttFixedHeader(
                MqttMessageType.CONNECT, false, MqttQoS.AT_MOST_ONCE, false, 0);
        return new MqttConnectMessage(
                header,
                new io.netty.handler.codec.mqtt.MqttConnectVariableHeader(MqttVersion.MQTT_3_1_1.protocolName(), (byte) 4, false, false, false, 0, false, false, 60),
                new io.netty.handler.codec.mqtt.MqttConnectPayload(clientId, null, (byte[]) null, null, (byte[]) null));
    }
}
