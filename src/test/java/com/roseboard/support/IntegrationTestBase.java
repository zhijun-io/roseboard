package com.roseboard.support;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {
    @Autowired protected TenantMapper tenantMapper;
    @Autowired protected TenantProfileMapper tenantProfileMapper;
    @Autowired protected DeviceMapper deviceMapper;
    @Autowired protected DeviceProfileService profileService;

    protected UUID tenantId;

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:18-alpine")
                .withDatabaseName("roseboard")
                .withUsername("roseboard")
                .withPassword("roseboard");
    }

    public static GenericContainer<?> valkey() {
        return new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);
    }

    public static void registerProperties(DynamicPropertyRegistry registry,
                                          PostgreSQLContainer<?> postgres,
                                          GenericContainer<?> valkey) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    protected void seedTenant(String tenantProfileNamePrefix) {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName(tenantProfileNamePrefix + tenantProfileId);
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
    }

    protected UUID createDevice(String namePrefix) {
        var profile = profileService.ensureDefaultForTenant(tenantId);
        DeviceEntity device = new DeviceEntity();
        device.setId(UUID.randomUUID());
        device.setCreatedTime(System.currentTimeMillis());
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profile.getId());
        device.setName(namePrefix + "-" + device.getId());
        device.setType("default");
        var deviceData = JacksonUtils.newObjectNode();
        deviceData.put("active", true);
        device.setDeviceData(deviceData);
        device.setExternalId(UUID.randomUUID());
        deviceMapper.insert(device);
        return device.getId();
    }

    protected static void await(Check check, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met");
    }

    @FunctionalInterface
    protected interface Check {
        boolean ok();
    }
}
