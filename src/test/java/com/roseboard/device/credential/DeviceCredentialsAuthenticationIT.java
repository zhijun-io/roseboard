package com.roseboard.device.credential;

import com.roseboard.support.IntegrationTestBase;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Testcontainers
class DeviceCredentialsAuthenticationIT extends IntegrationTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
    }

    @Autowired
    DeviceCredentialService credentialsService;

    @BeforeEach
    void setUp() {
        seedTenant("device-credentials-tp-");
    }

    @Test
    void authenticatesAccessToken() {
        UUID deviceId = createDevice("access-token-device");
        var saved = credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, "token-abc", null);
        String token = saved.getCredentialsValue();

        DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateAccessToken(token);

        assertPrincipal(principal, deviceId);
    }

    @Test
    void authenticatesMqttBasic() {
        UUID deviceId = createDevice("mqtt-device");
        String body = "{\"clientId\":\"mqtt-client\",\"userName\":\"mqtt-user\",\"password\":\"mqtt-pass\"}";
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.MQTT_BASIC, null, body);

        DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateMqttCredentials(
                "mqtt-client", "mqtt-user", "mqtt-pass");

        assertPrincipal(principal, deviceId);
    }

    @Test
    void authenticatesX509Certificate() {
        UUID deviceId = createDevice("x509-device");
        String fingerprint = "sha256-deadbeef";
        String body = "{\"fingerprint\":\"" + fingerprint + "\"}";
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.X509_CERTIFICATE, fingerprint, body);

        DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateX509Certificate(fingerprint);

        assertPrincipal(principal, deviceId);
    }

    @Test
    void authenticatesLwm2mCredentials() {
        UUID deviceId = createDevice("lwm2m-device");
        String identity = "lwm2m-identity";
        String body = "{\"identity\":\"" + identity + "\",\"securityMode\":\"PSK\",\"key\":\"psk-secret\"}";
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.LWM2M_CREDENTIALS, identity, body);

        DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateLwm2m(identity, "psk-secret");

        assertPrincipal(principal, deviceId);
    }

    @Test
    void rejectsInvalidCredentialsWithoutPrincipal() {
        UUID deviceId = createDevice("reject-device");
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, "valid-token", null);

        assertNull(credentialsService.authenticateAccessToken("wrong-token"));
        assertNull(credentialsService.authenticateMqttCredentials("x", "y", "z"));
        assertNull(credentialsService.authenticateX509Certificate("missing"));
        assertNull(credentialsService.authenticateLwm2m("nope", "key"));
    }

    private static void assertPrincipal(DeviceCredentialService.DevicePrincipal principal, UUID deviceId) {
        assertEquals(deviceId, principal.deviceId());
    }
}
