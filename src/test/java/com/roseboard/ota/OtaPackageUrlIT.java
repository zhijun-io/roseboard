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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
class OtaPackageUrlIT {
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
    private UUID profileId;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("ota-url-" + tenantProfileId);
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
    void acceptsHttpsUrlAndRejectsBinaryUrlMixAndForbiddenScheme() {
        OtaPackageInfo urlPackage = catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-url", "1.0.0", null,
                OtaArtifactSource.EXTERNAL_URL,
                "https://cdn.example.com/fw.bin?token=super-secret",
                "fw.bin", "application/octet-stream"));
        assertEquals(OtaPackageStatus.PUBLISHED, urlPackage.status());
        assertEquals("https://cdn.example.com/fw.bin", urlPackage.externalUrl());
        assertFalse(OtaUrlSanitizer.containsSecret(urlPackage.externalUrl()));

        assertThrows(OtaPackageException.class, () -> catalog.create(new OtaPackageCreateRequest(
                tenantId, profileId, "firmware", "fw-mix", "1.0.1", null,
                OtaArtifactSource.BINARY,
                "https://cdn.example.com/fw.bin",
                "fw.bin", "application/octet-stream")));

        OtaPackageException scheme = assertThrows(OtaPackageException.class, () -> catalog.create(
                new OtaPackageCreateRequest(
                        tenantId, profileId, "firmware", "fw-ftp", "1.0.2", null,
                        OtaArtifactSource.EXTERNAL_URL,
                        "ftp://cdn.example.com/fw.bin",
                        "fw.bin", "application/octet-stream")));
        assertEquals(OtaPackageErrorCode.INVALID_URL, scheme.errorCode());
    }

    @Test
    void auditPayloadRedactsSecretQuery() {
        Map<String, Object> detail = OtaUrlSanitizer.auditDetail(
                "firmware", "fw-audit", "1.0.0",
                "https://cdn.example.com/fw.bin?token=super-secret", 0L);
        assertFalse(String.valueOf(detail).contains("super-secret"));
        assertFalse(String.valueOf(detail).contains("token="));
        assertEquals("https://cdn.example.com/fw.bin", detail.get("url"));
    }
}
