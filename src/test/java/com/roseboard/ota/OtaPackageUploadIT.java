package com.roseboard.ota;

import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
class OtaPackageUploadIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired DeviceProfileService profileService;
    @Autowired
    OtaPackageService catalog;
    @Autowired
    OtaPackageServiceImpl packageService;

    private UUID tenantId;
    private UUID profileId;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("ota-up-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);
        tenantId = UUID.randomUUID();
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);
        profileId = profileService.ensureDefaultForTenant(tenantId).getId();
    }

    @Test
    void streamsBinaryUploadAndDownloadsExactBytes() throws Exception {
        byte[] payload = "firmware-bytes-12345".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(payload);
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-up", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));

        OtaPackageInfo published = packageService.uploadAndPublish(
                tenantId, draft.id(), new ByteArrayInputStream(payload), digest, "SHA-256");

        assertEquals(OtaPackageStatus.PUBLISHED, published.status());
        assertEquals(payload.length, published.sizeBytes());
        assertEquals(digest, published.checksumValue());
        assertArrayEquals(payload, packageService.download(tenantId, draft.id()).readAllBytes());
    }

    @Test
    void rejectsChecksumMismatchAndLeavesNoDownloadableArtifact() throws Exception {
        byte[] payload = "bad-check".getBytes(StandardCharsets.UTF_8);
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-bad", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));

        OtaPackageException mismatch = assertThrows(OtaPackageException.class, () ->
                packageService.uploadAndPublish(
                        tenantId, draft.id(), new ByteArrayInputStream(payload), "deadbeef", "SHA-256"));
        assertEquals(OtaPackageErrorCode.CHECKSUM_MISMATCH, mismatch.errorCode());
        assertEquals(OtaPackageStatus.DRAFT, catalog.findById(tenantId, draft.id()).status());
        assertThrows(OtaPackageException.class, () -> packageService.download(tenantId, draft.id()));
    }

    @Test
    void storesChecksumUsingRequestedAlgorithm() throws Exception {
        byte[] payload = "sha384-payload".getBytes(StandardCharsets.UTF_8);
        String digest = digest(payload, "SHA-384");
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-sha384", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));

        OtaPackageInfo published = packageService.uploadAndPublish(
                tenantId, draft.id(), new ByteArrayInputStream(payload), digest, "SHA-384");

        assertEquals("SHA-384", published.checksumAlgorithm());
        assertEquals(digest, published.checksumValue());
    }

    @Test
    void rejectsMutationOfPublishedImmutableFields() throws Exception {
        byte[] payload = "immutable".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(payload);
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-imm", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
        packageService.uploadAndPublish(tenantId, draft.id(), new ByteArrayInputStream(payload), digest, "SHA-256");

        OtaPackageException immutable = assertThrows(OtaPackageException.class, () ->
                packageService.updateMutableFields(tenantId, draft.id(), "new-title", "2.0.0", "tag"));
        assertEquals(OtaPackageErrorCode.IMMUTABLE_FIELD, immutable.errorCode());
        assertArrayEquals(payload, packageService.download(tenantId, draft.id()).readAllBytes());
        assertEquals("fw-imm", catalog.findById(tenantId, draft.id()).title());
    }

    private static String sha256(byte[] payload) throws Exception {
        return digest(payload, "SHA-256");
    }

    private static String digest(byte[] payload, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(payload));
    }
}
