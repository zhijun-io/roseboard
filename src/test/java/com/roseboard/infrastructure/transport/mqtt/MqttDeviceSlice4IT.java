package com.roseboard.infrastructure.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.ota.OtaArtifactSource;
import com.roseboard.ota.OtaPackageCreateRequest;
import com.roseboard.ota.OtaPackageInfo;
import com.roseboard.ota.OtaPackageService;
import com.roseboard.ota.OtaPackageServiceImpl;
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
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MqttDeviceSlice4IT extends IntegrationTestBase {
    private static final String PROVISION_SECRET = "mqtt-slice4-provision-secret";

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
    @Autowired DeviceAttributeService attributeService;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceMapper deviceMapper;
    @Autowired OtaPackageService otaPackageService;
    @Autowired OtaPackageServiceImpl otaPackageServiceImpl;

    private String provisionKey;
    private UUID deviceId;
    private UUID profileId;
    private String deviceToken;
    private Mqtt3AsyncClient client;

    @BeforeEach
    void seed() {
        seedTenant("mqtt-slice4-tp-");
        provisionKey = "mqtt-slice4-key-" + UUID.randomUUID();
        profileId = configureProvisionProfile();
        deviceId = createDevice("mqtt-slice4-device");
        deviceToken = "mqtt-slice4-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @AfterEach
    void disconnect() {
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    @Test
    void claimStoresServerAttributes() throws Exception {
        connect(deviceToken);
        client.publishWith()
                .topic(MqttTopics.Device.CLAIM)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"secretKey\":\"claim-secret\",\"durationMs\":60000}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        await(() -> {
            var value = attributeService.find(tenantId, deviceId, AttributeScope.SERVER, new AttributeKey("claimingData"));
            return value != null && value.value().value().toString().contains("claim-secret");
        }, 5);
    }

    @Test
    void provisionCreatesDeviceWithAccessToken() throws Exception {
        String deviceName = "mqtt-provisioned-" + UUID.randomUUID();
        connectProvision();
        CompletableFuture<Mqtt3Publish> responseFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, responseFuture::complete);
        client.subscribeWith()
                .topicFilter(MqttTopics.Provision.RESPONSE)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        client.publishWith()
                .topic(MqttTopics.Provision.REQUEST)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(("""
                        {
                          "deviceName":"%s",
                          "provisionDeviceKey":"%s",
                          "provisionDeviceSecret":"%s"
                        }
                        """.formatted(deviceName, provisionKey, PROVISION_SECRET)).getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        String response = new String(responseFuture.get(5, TimeUnit.SECONDS).getPayloadAsBytes(), StandardCharsets.UTF_8);
        assertTrue(response.contains("\"status\":\"SUCCESS\""));
        assertTrue(response.contains("\"credentialsType\":\"ACCESS_TOKEN\""));

        DeviceEntity created = deviceMapper.findByTenantAndName(tenantId, deviceName);
        assertNotNull(created);
        assertEquals(profileId, created.getDeviceProfileId());
    }

    @Test
    void firmwareChunkDownloadMatchesAssignedPackage() throws Exception {
        byte[] payload = "firmware-mqtt-s4".getBytes(StandardCharsets.UTF_8);
        UUID packageId = publishFirmware("fw-mqtt-s4", "1.0.0", payload, "fw-mqtt-s4.bin");
        DeviceEntity device = deviceMapper.selectById(deviceId);
        device.setFirmwareId(packageId);
        deviceMapper.updateById(device);

        connect(deviceToken);
        CompletableFuture<Mqtt3Publish> chunkFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, chunkFuture::complete);
        client.subscribeWith()
                .topicFilter(MqttTopics.Ota.FIRMWARE_RESPONSE_FILTER)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        client.publishWith()
                .topic(MqttTopics.Ota.FIRMWARE_REQUEST_PREFIX + "1/chunk/0")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"title\":\"fw-mqtt-s4\",\"version\":\"1.0.0\",\"size\":0}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        byte[] chunk = chunkFuture.get(5, TimeUnit.SECONDS).getPayloadAsBytes();
        assertEquals("firmware-mqtt-s4", new String(chunk, StandardCharsets.UTF_8));
    }

    @Test
    void softwareChunkDownloadSupportsOffset() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        UUID packageId = publishSoftware("sw-mqtt-s4", "2.0.0", payload, "sw-mqtt-s4.bin");
        DeviceEntity device = deviceMapper.selectById(deviceId);
        device.setSoftwareId(packageId);
        deviceMapper.updateById(device);

        connect(deviceToken);
        CompletableFuture<Mqtt3Publish> chunkFuture = new CompletableFuture<>();
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, chunkFuture::complete);
        client.subscribeWith()
                .topicFilter(MqttTopics.Ota.SOFTWARE_RESPONSE_FILTER)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .get(5, TimeUnit.SECONDS);

        client.publishWith()
                .topic(MqttTopics.Ota.SOFTWARE_REQUEST_PREFIX + "2/chunk/1")
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("{\"title\":\"sw-mqtt-s4\",\"version\":\"2.0.0\",\"size\":8}".getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        byte[] chunk = chunkFuture.get(5, TimeUnit.SECONDS).getPayloadAsBytes();
        assertEquals("89abcdef", new String(chunk, StandardCharsets.UTF_8));
    }

    private void connect(String token) {
        int port = mqttServer.boundPort();
        client = MqttClient.builder()
                .identifier("mqtt-s4-it-" + UUID.randomUUID())
                .serverHost("127.0.0.1")
                .serverPort(port)
                .useMqttVersion3()
                .buildAsync();
        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, client.connectWith()
                .simpleAuth()
                .username(token)
                .applySimpleAuth()
                .send()
                .join()
                .getReturnCode());
    }

    private void connectProvision() {
        int port = mqttServer.boundPort();
        client = MqttClient.builder()
                .identifier(MqttTopics.Provision.CLIENT_ID)
                .serverHost("127.0.0.1")
                .serverPort(port)
                .useMqttVersion3()
                .buildAsync();
        assertEquals(Mqtt3ConnAckReturnCode.SUCCESS, client.connectWith()
                .simpleAuth()
                .username(MqttTopics.Provision.CLIENT_ID)
                .applySimpleAuth()
                .send()
                .join()
                .getReturnCode());
    }

    private UUID configureProvisionProfile() {
        DeviceProfileEntity profile = profileService.ensureDefaultForTenant(tenantId);
        ObjectNode profileData = JacksonUtils.newObjectNode();
        profileData.put("provisionDeviceKey", provisionKey);
        ObjectNode provisionConfiguration = JacksonUtils.newObjectNode();
        provisionConfiguration.put("type", "ALLOW_CREATE_NEW_DEVICES");
        provisionConfiguration.put("provisionDeviceSecret", PROVISION_SECRET);
        profileData.set("provisionConfiguration", provisionConfiguration);
        profile.setProfileData(profileData);
        profileService.saveForTenant(tenantId, profile, profile.getVersion());
        return profile.getId();
    }

    private UUID publishFirmware(String title, String version, byte[] payload, String fileName) throws Exception {
        return publishPackage("firmware", title, version, payload, fileName);
    }

    private UUID publishSoftware(String title, String version, byte[] payload, String fileName) throws Exception {
        return publishPackage("software", title, version, payload, fileName);
    }

    private UUID publishPackage(String kind, String title, String version, byte[] payload, String fileName)
            throws Exception {
        OtaPackageInfo draft = otaPackageService.create(new OtaPackageCreateRequest(
                tenantId, profileId, kind, title, version, null,
                OtaArtifactSource.BINARY, null, fileName, "application/octet-stream"));
        otaPackageServiceImpl.uploadAndPublish(
                tenantId, draft.id(), new ByteArrayInputStream(payload), sha256(payload), "SHA-256");
        return draft.id();
    }

    private static String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
