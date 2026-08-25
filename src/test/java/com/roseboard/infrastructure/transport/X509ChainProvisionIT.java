package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
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
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Testcontainers
class X509ChainProvisionIT extends IntegrationTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
    }

    @Autowired TransportDeviceApiService transportDeviceApiService;
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceMapper deviceMapper;
    @Autowired DeviceAttributeService attributeService;

    private String certificateChain;
    private String leafCertificate;
    private String caCertificate;
    private UUID x509ProfileId;

    @BeforeEach
    void setUp() throws Exception {
        seedTenant("x509-provision-tp-");
        String chain = Files.readString(Path.of("src/test/resources/provision/x509ChainProvisionTest.pem"));
        List<String> certificates = X509CertificateUtil.splitCertificateChain(chain);
        leafCertificate = certificates.getFirst();
        caCertificate = certificates.get(1);
        certificateChain = leafCertificate + "\n" + caCertificate;
        x509ProfileId = configureX509Profile(false);
    }

    @Test
    void x509ChainProvisionFlow() {
        assertNull(transportDeviceApiService.resolveX509CertificateChain(certificateChain));

        configureX509Profile(true);
        DeviceCredentialService.DevicePrincipal created =
                transportDeviceApiService.resolveX509CertificateChain(certificateChain);
        assertNotNull(created);
        DeviceEntity device = deviceMapper.selectById(created.deviceId());
        assertEquals("deviceCertificate", device.getName());
        assertEquals(x509ProfileId, device.getDeviceProfileId());
        assertEquals("provisioned", attributeService.find(tenantId, device.getId(), AttributeScope.SERVER,
                new AttributeKey("provisionState")).value().value());

        DeviceCredentialService.DevicePrincipal updated =
                transportDeviceApiService.resolveX509CertificateChain(certificateChain);
        assertNotNull(updated);
        assertEquals(created.deviceId(), updated.deviceId());
        assertNotNull(credentialsService.authenticateX509Certificate(X509CertificateUtil.sha3HashHex(leafCertificate)));
    }

    private UUID configureX509Profile(boolean allowCreate) {
        DeviceProfileEntity profile = profileService.ensureDefaultForTenant(tenantId);
        ObjectNode profileData = JacksonUtils.newObjectNode();
        profileData.put("provisionDeviceKey", X509CertificateUtil.sha3HashHex(caCertificate));
        ObjectNode provisionConfiguration = JacksonUtils.newObjectNode();
        provisionConfiguration.put("type", "X509_CERTIFICATE_CHAIN");
        provisionConfiguration.put("provisionDeviceSecret", caCertificate);
        provisionConfiguration.put("certificateRegExPattern", "([^@]+)");
        provisionConfiguration.put("allowCreateNewDevicesByX509Certificate", allowCreate);
        profileData.set("provisionConfiguration", provisionConfiguration);
        profile.setProfileData(profileData);
        profileService.saveForTenant(tenantId, profile, profile.getVersion());
        return profile.getId();
    }
}
