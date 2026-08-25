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
class OtaPackageDownloadDeleteQuotaIT {
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
        registry.add("roseboard.ota.max-package-bytes", () -> "32");
        registry.add("roseboard.ota.max-tenant-bytes", () -> "64");
    }

    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired DeviceProfileService profileService;
    @Autowired
    OtaPackageService catalog;
    @Autowired
    OtaPackageServiceImpl packageService;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID profileId;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("ota-dl-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        insertTenant(tenantId, tenantProfileId);
        insertTenant(otherTenantId, tenantProfileId);
        profileId = profileService.ensureDefaultForTenant(tenantId).getId();
    }

    @Test
    void supportsRangeDownloadAndTenantIsolation() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        OtaPackageInfo published = publish(tenantId, profileId, "fw-range", "1.0.0", payload);

        byte[] range = packageService.downloadRange(tenantId, published.id(), 4, 4);
        assertArrayEquals("4567".getBytes(StandardCharsets.UTF_8), range);

        assertThrows(OtaPackageException.class,
                () -> packageService.download(otherTenantId, published.id()));
    }

    @Test
    void rejectsInvalidRangeWithoutReadingArtifact() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        OtaPackageInfo published = publish(tenantId, profileId, "fw-invalid-range", "1.0.0", payload);

        OtaPackageException zeroLength = assertThrows(OtaPackageException.class,
                () -> packageService.downloadRange(tenantId, published.id(), 0, 0));
        assertEquals(OtaPackageErrorCode.INVALID_REQUEST, zeroLength.errorCode());

        assertArrayEquals("123456789abcdef".getBytes(StandardCharsets.UTF_8),
                packageService.downloadRange(tenantId, published.id(), 1, Long.MAX_VALUE));

        OtaPackageException negativeLength = assertThrows(OtaPackageException.class,
                () -> packageService.downloadRange(tenantId, published.id(), 1, -1));
        assertEquals(OtaPackageErrorCode.INVALID_REQUEST, negativeLength.errorCode());
    }

    @Test
    void deletesMetadataAndArtifactTogether() throws Exception {
        byte[] payload = "to-delete".getBytes(StandardCharsets.UTF_8);
        OtaPackageInfo published = publish(tenantId, profileId, "fw-del", "1.0.0", payload);
        packageService.delete(tenantId, published.id());
        assertThrows(OtaPackageException.class, () -> catalog.findById(tenantId, published.id()));
        assertThrows(OtaPackageException.class, () -> packageService.download(tenantId, published.id()));
    }

    @Test
    void rejectsOversizedPackageBeforePublish() {
        byte[] payload = "0123456789abcdef0123456789abcdef!".getBytes(StandardCharsets.UTF_8);
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-quota", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
        OtaPackageException quota = assertThrows(OtaPackageException.class, () ->
                packageService.uploadAndPublish(tenantId, draft.id(),
                        new ByteArrayInputStream(payload), sha256(payload), "SHA-256"));
        assertEquals(OtaPackageErrorCode.QUOTA_EXCEEDED, quota.errorCode());
        assertEquals(OtaPackageStatus.DRAFT, catalog.findById(tenantId, draft.id()).status());
    }

    private OtaPackageInfo publish(UUID tenant, UUID profile, String title, String version, byte[] payload)
            throws Exception {
        OtaPackageInfo draft = catalog.create(new OtaPackageCreateRequest(
                tenant, profile, "firmware", title, version, null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
        return packageService.uploadAndPublish(tenant, draft.id(),
                new ByteArrayInputStream(payload), sha256(payload), "SHA-256");
    }

    private void insertTenant(UUID id, UUID tenantProfileId) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);
    }

    private static String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
