package com.roseboard.infrastructure.transport.http;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class HttpDeviceSlice4IT extends IntegrationTestBase {
    private String provisionKey;
    private static final String PROVISION_SECRET = "slice4-provision-secret";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
    }

    @Autowired MockMvc mockMvc;
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired DeviceAttributeService attributeService;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceMapper deviceMapper;
    @Autowired OtaPackageService otaPackageService;
    @Autowired OtaPackageServiceImpl otaPackageServiceImpl;

    private UUID deviceId;
    private UUID profileId;
    private String deviceToken;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("http-slice4-tp-");
        provisionKey = "slice4-key-" + UUID.randomUUID();
        profileId = configureProvisionProfile();
        deviceId = createDevice("http-slice4-device");
        deviceToken = "http-slice4-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @Test
    void claimStoresServerAttributes() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/{token}/claim", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"secretKey":"claim-secret","durationMs":60000}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        String claimData = attributeService.find(tenantId, deviceId, AttributeScope.SERVER,
                new AttributeKey("claimingData")).value().value().toString();
        assertTrue(claimData.contains("claim-secret"));
        assertTrue(claimData.contains("expirationTime"));
    }

    @Test
    void claimRejectsInvalidToken() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/{token}/claim", "bad-token"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isUnauthorized());
    }

    @Test
    void provisionCreatesDeviceWithAccessToken() throws Exception {
        String deviceName = "provisioned-device-" + UUID.randomUUID();

        MvcResult started = mockMvc.perform(post("/api/http/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceName":"%s",
                                  "provisionDeviceKey":"%s",
                                  "provisionDeviceSecret":"%s"
                                }
                                """.formatted(deviceName, provisionKey, PROVISION_SECRET)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.credentialsType").value("ACCESS_TOKEN"))
                .andExpect(jsonPath("$.credentialsValue").isNotEmpty());

        DeviceEntity created = deviceMapper.findByTenantAndName(tenantId, deviceName);
        assertNotNull(created);
        assertEquals(profileId, created.getDeviceProfileId());
    }

    @Test
    void provisionReturnsNotFoundForWrongSecret() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceName":"ghost-device",
                                  "provisionDeviceKey":"%s",
                                  "provisionDeviceSecret":"wrong-secret"
                                }
                                """.formatted(provisionKey)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void provisionRejectsMalformedRequest() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isInternalServerError());
    }

    @Test
    void provisionPreProvisionedDeviceReturnsExistingCredentials() throws Exception {
        ObjectNode profileData = JacksonUtils.newObjectNode();
        profileData.put("provisionDeviceKey", provisionKey);
        ObjectNode provisionConfiguration = JacksonUtils.newObjectNode();
        provisionConfiguration.put("type", "CHECK_PRE_PROVISIONED_DEVICES");
        provisionConfiguration.put("provisionDeviceSecret", PROVISION_SECRET);
        profileData.set("provisionConfiguration", provisionConfiguration);
        DeviceProfileEntity profile = profileService.findById(profileId);
        profile.setProfileData(profileData);
        profileService.saveForTenant(tenantId, profile, profile.getVersion());

        DeviceEntity device = deviceMapper.selectById(deviceId);
        String name = device.getName();

        MvcResult started = mockMvc.perform(post("/api/http/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceName":"%s",
                                  "provisionDeviceKey":"%s",
                                  "provisionDeviceSecret":"%s"
                                }
                                """.formatted(name, provisionKey, PROVISION_SECRET)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.credentialsValue").value(deviceToken));
    }

    @Test
    void firmwareDownloadMatchesAssignedPackage() throws Exception {
        byte[] payload = "firmware-slice4".getBytes(StandardCharsets.UTF_8);
        UUID packageId = publishFirmware("fw-s4", "1.0.0", payload, "fw-s4.bin");
        DeviceEntity device = deviceMapper.selectById(deviceId);
        device.setFirmwareId(packageId);
        deviceMapper.updateById(device);

        MvcResult started = mockMvc.perform(get("/api/http/{token}/firmware", deviceToken)
                        .param("title", "fw-s4")
                        .param("version", "1.0.0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("x-filename", "fw-s4.bin"))
                .andExpect(header().string("Content-Disposition", "attachment;filename=fw-s4.bin"));
    }

    @Test
    void firmwareDownloadRejectsMismatchedTitleOrVersion() throws Exception {
        byte[] payload = "firmware-slice4".getBytes(StandardCharsets.UTF_8);
        UUID packageId = publishFirmware("fw-s4-mismatch", "1.0.0", payload, "fw.bin");
        DeviceEntity device = deviceMapper.selectById(deviceId);
        device.setFirmwareId(packageId);
        deviceMapper.updateById(device);

        MvcResult started = mockMvc.perform(get("/api/http/{token}/firmware", deviceToken)
                        .param("title", "other-title")
                        .param("version", "1.0.0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isBadRequest());
    }

    @Test
    void softwareDownloadSupportsChunkParameters() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        UUID packageId = publishSoftware("sw-s4", "2.0.0", payload, "sw-s4.bin");
        DeviceEntity device = deviceMapper.selectById(deviceId);
        device.setSoftwareId(packageId);
        deviceMapper.updateById(device);

        MvcResult started = mockMvc.perform(get("/api/http/{token}/software", deviceToken)
                        .param("title", "sw-s4")
                        .param("version", "2.0.0")
                        .param("size", "8")
                        .param("chunk", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("89abcdef", new String(completed.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
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
