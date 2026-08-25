package com.roseboard.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import com.roseboard.notification.channel.ChannelConfigService;
import com.roseboard.notification.channel.MybatisNotificationChannelConfigResolver;
import com.roseboard.notification.channel.NotificationChannelConfigEntity;
import com.roseboard.notification.channel.NotificationChannelConfigMapper;
import com.roseboard.setting.AdminSettingEntity;
import com.roseboard.setting.AdminSettingMapper;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class ChannelConfigMigrationIT {
    private static final UUID SYSTEM_TENANT = new UUID(0, 0);
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000941");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000942");

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

    @Autowired
    AdminSettingMapper adminSettingMapper;
    @Autowired NotificationChannelConfigMapper channelConfigMapper;
    @Autowired ChannelConfigService channelConfigService;
    @Autowired
    MybatisNotificationChannelConfigResolver channelConfigSource;
    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedTenant() {
        channelConfigMapper.delete(new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                .eq(NotificationChannelConfigEntity::getTenantId, TENANT_ID));
        tenantMapper.deleteById(TENANT_ID);
        tenantProfileMapper.deleteById(PROFILE_ID);

        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(PROFILE_ID);
        profile.setCreatedTime(System.currentTimeMillis());
        profile.setName("channel-config-it");
        profile.setIsDefault(false);
        profile.setIsolatedTbCore(false);
        profile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(profile);

        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(PROFILE_ID);
        tenant.setTitle("Channel config tenant");
        tenant.setVersion(1L);
        tenantMapper.insert(tenant);
    }

    @Test
    void legacyAdminSettingsMailAndSmsKeysAreRemovedAfterV61() {
        assertNull(adminSettingMapper.selectOne(new LambdaQueryWrapper<AdminSettingEntity>()
                .eq(AdminSettingEntity::getTenantId, SYSTEM_TENANT)
                .eq(AdminSettingEntity::getKey, "mail")));
        assertNull(adminSettingMapper.selectOne(new LambdaQueryWrapper<AdminSettingEntity>()
                .eq(AdminSettingEntity::getTenantId, SYSTEM_TENANT)
                .eq(AdminSettingEntity::getKey, "sms")));
    }

    @Test
    void platformChannelCanBeSavedAndResolved() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("smtpHost", "smtp.example.com");
        config.put("smtpPort", 587);
        ObjectNode secrets = objectMapper.createObjectNode();
        secrets.put("password", "secret");
        channelConfigService.savePlatform(ChannelKind.EMAIL,
                new ChannelConfigService.ChannelUpdate(true, null, config, secrets));

        Optional<ResolvedChannelConfig> resolved = channelConfigSource.resolve(null, ChannelKind.EMAIL);
        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().isComplete());
    }

    @Test
    void platformDisabledBlocksInheritedTenantEmail() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("smtpHost", "smtp.example.com");
        config.put("smtpPort", 587);
        channelConfigService.savePlatform(ChannelKind.EMAIL,
                new ChannelConfigService.ChannelUpdate(false, null, config, objectMapper.createObjectNode()));

        NotificationChannelConfigEntity tenant = new NotificationChannelConfigEntity();
        tenant.setId(UUID.randomUUID());
        tenant.setTenantId(TENANT_ID);
        tenant.setChannelKind("EMAIL");
        tenant.setEnabled(true);
        tenant.setConnectionMode("INHERIT");
        tenant.setConfig(objectMapper.createObjectNode());
        tenant.setSecrets(objectMapper.createObjectNode());
        long now = System.currentTimeMillis();
        tenant.setCreatedTime(now);
        tenant.setUpdatedTime(now);
        channelConfigMapper.insert(tenant);

        Optional<ResolvedChannelConfig> resolved = channelConfigSource.resolve(TENANT_ID, ChannelKind.EMAIL);
        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().enabled());
    }
}
