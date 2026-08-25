package com.roseboard.notification;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ConfigScope;
import com.roseboard.infrastructure.notification.model.ConnectionMode;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import com.roseboard.notification.channel.MybatisNotificationChannelConfigResolver;
import com.roseboard.notification.catalog.NotificationTargetEntity;
import com.roseboard.notification.catalog.NotificationTargetMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationPhase3HttpIT {
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TENANT_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PROFILE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001");
    private static final UUID PROFILE_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0001");
    private static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0002");
    private static final UUID ADMIN_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0002");
    private static final String PASSWORD = "Notification-phase3-password1!";

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
        registry.add("roseboard.security.mfa.enabled", () -> false);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired
    MybatisNotificationChannelConfigResolver channelConfigSource;
    @Autowired NotificationMapper notificationMapper;
    @Autowired NotificationTargetMapper targetMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        notificationMapper.delete(new LambdaQueryWrapper<NotificationEntity>()
                .in(NotificationEntity::getScopeId, TENANT_B, TENANT_C));
        targetMapper.delete(new LambdaQueryWrapper<NotificationTargetEntity>()
                .in(NotificationTargetEntity::getTenantId, TENANT_B, TENANT_C));
        userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .in(UserCredentialEntity::getUserId, ADMIN_B, ADMIN_C));
        userMapper.delete(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getId, ADMIN_B, ADMIN_C));
        tenantMapper.delete(new LambdaQueryWrapper<TenantEntity>()
                .in(TenantEntity::getId, TENANT_B, TENANT_C));
        tenantProfileMapper.delete(new LambdaQueryWrapper<TenantProfileEntity>()
                .in(TenantProfileEntity::getId, PROFILE_B, PROFILE_C));

        insertTenant(TENANT_B, PROFILE_B, "tenant-b");
        insertTenant(TENANT_C, PROFILE_C, "tenant-c");
        insertAdmin(ADMIN_B, TENANT_B, "phase3-admin-b@example.com");
        insertAdmin(ADMIN_C, TENANT_C, "phase3-admin-c@example.com");
    }

    @Test
    void tenantCustomSmtpIsIsolatedFromInheritTenant() throws Exception {
        String tokenC = login("phase3-admin-c@example.com");
        mockMvc.perform(put("/api/notifications/channels/EMAIL")
                        .header("Authorization", bearer(tokenC))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"connectionMode":"CUSTOM",
                                "config":{"smtpHost":"smtp.customer.test","smtpPort":587,"from":"alert@customer.test","tls":true},
                                "secrets":{"password":"customer-smtp-secret"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionMode").value("CUSTOM"))
                .andExpect(jsonPath("$.config.smtpHost").value("smtp.customer.test"));

        Optional<ResolvedChannelConfig> tenantC = channelConfigSource.resolve(TENANT_C, ChannelKind.EMAIL);
        assertTrue(tenantC.isPresent());
        assertEquals(ConfigScope.TENANT, tenantC.get().scope());
        assertEquals(ConnectionMode.CUSTOM, tenantC.get().connectionMode());
        assertEquals("smtp.customer.test", tenantC.get().config().get("smtpHost").toString());

        Optional<ResolvedChannelConfig> tenantB = channelConfigSource.resolve(TENANT_B, ChannelKind.EMAIL);
        assertTrue(tenantB.isPresent());
        assertEquals(ConnectionMode.INHERIT, tenantB.get().connectionMode());
    }

    @Test
    void inheritTenantChannelViewDoesNotExposePlatformSecrets() throws Exception {
        String token = login("phase3-admin-b@example.com");
        mockMvc.perform(get("/api/notifications/channels/EMAIL").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionMode").value("INHERIT"))
                .andExpect(jsonPath("$.config").isEmpty())
                .andExpect(jsonPath("$.secrets").isEmpty());
    }

    @Test
    void manualSendToUserIdsCreatesInboxEntry() throws Exception {
        String token = login("phase3-admin-b@example.com");
        mockMvc.perform(post("/api/notifications/deliveries")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"usage.threshold","channels":["WEB"],
                                "userIds":["%s"],
                                "vars":{"status":"WARNING","metricKey":"maxEmails","period":"2026-02"}}
                                """.formatted(ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byChannel.WEB.status").value("SUCCESS"));

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].body").value(org.hamcrest.Matchers.containsString("maxEmails")));
    }

    @Test
    void manualSendViaTargetDeliversToAllTenantAdmins() throws Exception {
        String token = login("phase3-admin-b@example.com");
        String targetResponse = mockMvc.perform(post("/api/notifications/targets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admins\",\"targetType\":\"TENANT_ADMINS\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String targetId = objectMapper.readTree(targetResponse).get("id").asText();

        mockMvc.perform(post("/api/notifications/deliveries")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"usage.threshold","channels":["WEB"],
                                "targetId":"%s",
                                "vars":{"status":"EXCEEDED","metricKey":"maxDevices","period":"2026-02"}}
                                """.formatted(targetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byChannel.WEB.status").value("SUCCESS"));

        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, ADMIN_B)
                .eq(NotificationEntity::getStatus, "UNREAD"));
        assertEquals(1L, unread);
    }

    @Test
    void manualSendRejectsSystemTemplate() throws Exception {
        String token = login("phase3-admin-b@example.com");
        mockMvc.perform(post("/api/notifications/deliveries")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"auth.activation","channels":["WEB"],
                                "userIds":["%s"],"vars":{"email":"x@example.com","link":"https://example.com"}}
                                """.formatted(ADMIN_B)))
                .andExpect(status().isForbidden());
    }


    private void insertTenant(UUID tenantId, UUID profileId, String title) {
        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(profileId);
        profile.setCreatedTime(System.currentTimeMillis());
        profile.setName(title + "-profile");
        profile.setIsDefault(false);
        profile.setIsolatedTbCore(false);
        profile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(profile);

        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(profileId);
        tenant.setTitle(title);
        tenant.setVersion(1L);
        tenantMapper.insert(tenant);
    }

    private void insertAdmin(UUID userId, UUID tenantId, String email) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setAuthority(UserAuthority.TENANT_ADMIN);
        user.setVersion(1L);
        userMapper.insert(user);
        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(userId);
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        userCredentialMapper.insert(credentials);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
