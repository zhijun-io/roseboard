package com.roseboard.ota;

import com.roseboard.device.profile.DeviceProfileEntity;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class OtaPackageMetadataIT {
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

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID profileId;
    private UUID otherProfileId;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("ota-profile-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);

        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        insertTenant(tenantId, tenantProfileId);
        insertTenant(otherTenantId, tenantProfileId);
        profileId = profileService.ensureDefaultForTenant(tenantId).getId();
        otherProfileId = profileService.ensureDefaultForTenant(otherTenantId).getId();
    }

    @Test
    void createsBuiltinFirmwareAndSoftwarePackages() {
        OtaPackageInfo firmware = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
        OtaPackageInfo software = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "software", "app", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "app.bin", "application/octet-stream"));

        assertEquals("firmware", firmware.kind());
        assertEquals("software", software.kind());
        assertEquals(OtaPackageStatus.DRAFT, firmware.status());
        assertEquals(firmware.id(), catalog.findById(tenantId, firmware.id()).id());
    }

    @Test
    void rejectsUnknownKindAndEnforcesTenantTitleVersionUniqueness() {
        assertThrows(OtaPackageException.class, () -> catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "unknown-kind", "x", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "x.bin", "application/octet-stream")));

        catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));

        OtaPackageException duplicate = assertThrows(OtaPackageException.class, () -> catalog.create(
                new OtaPackageCreateRequest(
                        tenantId, profileId, "firmware", "fw", "1.0.0", null,
                        OtaArtifactSource.BINARY, null, "fw2.bin", "application/octet-stream")));
        assertEquals(OtaPackageErrorCode.DUPLICATE_PACKAGE, duplicate.errorCode());

        OtaPackageInfo otherTenant = catalog.create(new OtaPackageCreateRequest(
                otherTenantId, otherProfileId, "firmware", "fw", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
        assertEquals(otherTenantId, otherTenant.tenantId());
    }

    @Test
    void rejectsCrossTenantProfileBindingAndOversizedFields() {
        OtaPackageException crossTenant = assertThrows(OtaPackageException.class, () -> catalog.create(
                new OtaPackageCreateRequest(
                        tenantId, otherProfileId, "firmware", "fw", "2.0.0", null,
                        OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream")));
        assertEquals(OtaPackageErrorCode.INVALID_PROFILE, crossTenant.errorCode());

        assertThrows(OtaPackageException.class, () -> catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "t".repeat(OtaPackageLimits.MAX_TITLE_CHARS + 1), "1.0.0",
                null, OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream")));
    }

    @Test
    void rejectsUnknownPackageTypeAndDeviceProfileCarriesTbPackageIds() {
        OtaPackageException unknown = assertThrows(OtaPackageException.class, () -> catalog.create(
                new OtaPackageCreateRequest(
                        tenantId, profileId, "custom-kind", "x", "1.0.0", null,
                        OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream")));
        assertEquals(OtaPackageErrorCode.UNKNOWN_KIND, unknown.errorCode());

        boolean hasFirmwareColumn = java.util.Arrays.stream(DeviceProfileEntity.class.getDeclaredFields())
                .anyMatch(field -> "firmwareId".equals(field.getName()));
        boolean hasSoftwareColumn = java.util.Arrays.stream(DeviceProfileEntity.class.getDeclaredFields())
                .anyMatch(field -> "softwareId".equals(field.getName()));
        assertTrue(hasFirmwareColumn && hasSoftwareColumn);

        catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "schema-check", "1.0.0", null,
                OtaArtifactSource.BINARY, null, "fw.bin", "application/octet-stream"));
    }

    private void insertTenant(UUID id, UUID tenantProfileId) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);
    }
}
