package com.roseboard.notification;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.*;
import com.roseboard.infrastructure.notification.model.*;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.notification.template.NotificationTemplateEntity;
import com.roseboard.notification.template.NotificationTemplateMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import com.roseboard.user.*;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationCatalogHttpIT {
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TENANT_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PROFILE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001");
    private static final UUID PROFILE_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddd0001");
    private static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0002");
    private static final UUID ADMIN_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddd0002");
    private static final String PASSWORD = "Notification-catalog-password1!";

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
    NotificationCenter notificationCenter;
    @Autowired NotificationService notificationService;
    @Autowired NotificationMapper notificationMapper;
    @Autowired NotificationTemplateMapper templateMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        templateMapper.delete(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .in(NotificationTemplateEntity::getTenantId, TENANT_B, TENANT_D));
        notificationMapper.delete(new LambdaQueryWrapper<NotificationEntity>()
                .in(NotificationEntity::getScopeId, TENANT_B, TENANT_D));
        userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .in(UserCredentialEntity::getUserId, ADMIN_B, ADMIN_D));
        userMapper.delete(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getId, ADMIN_B, ADMIN_D));
        tenantMapper.delete(new LambdaQueryWrapper<TenantEntity>()
                .in(TenantEntity::getId, TENANT_B, TENANT_D));
        tenantProfileMapper.delete(new LambdaQueryWrapper<TenantProfileEntity>()
                .in(TenantProfileEntity::getId, PROFILE_B, PROFILE_D));

        insertTenant(TENANT_B, PROFILE_B, "tenant-b");
        insertTenant(TENANT_D, PROFILE_D, "tenant-d");
        insertAdmin(ADMIN_B, TENANT_B, "catalog-admin-b@example.com");
        insertAdmin(ADMIN_D, TENANT_D, "catalog-admin-d@example.com");
    }

    @Test
    void tenantCannotOverrideAuthTemplate() throws Exception {
        String token = login("catalog-admin-b@example.com");
        mockMvc.perform(put("/api/notifications/templates/auth.activation")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Malicious","renderEngine":"SIMPLE","notificationType":"AUTH",
                                "deliveryMethods":{"EMAIL":{"enabled":true,"subject":"Hacked","body":"Hacked","html":false}}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUsageTemplateOverrideAppearsInInbox() throws Exception {
        String token = login("catalog-admin-b@example.com");
        mockMvc.perform(put("/api/notifications/templates/usage.threshold")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Usage (tenant B)","renderEngine":"SIMPLE","notificationType":"USAGE_LIMIT",
                                "deliveryMethods":{"WEB":{"enabled":true,"subject":"【告警】${status}",
                                "body":"【重要】${metricKey} 已${status}，周期 ${period}。"}}}
                                """))
                .andExpect(status().isOk());

        notificationCenter.notify(TENANT_B, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(ADMIN_B, "catalog-admin-b@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].body").value(org.hamcrest.Matchers.containsString("【重要】")));
    }

    @Test
    void tenantCanDisableEmailChannelWhileWebStillDelivers() throws Exception {
        String token = login("catalog-admin-d@example.com");
        mockMvc.perform(put("/api/notifications/channels/EMAIL")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        NotifyResult result = notificationCenter.notify(TENANT_D, new NotifyCommand(
                Set.of(ChannelKind.WEB, ChannelKind.EMAIL),
                List.of(new RecipientRef(ADMIN_D, "catalog-admin-d@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING", "metricKey", "maxEmails", "period", "2026-01"),
                new NotifyOptions(false, false)));

        assertEquals(DeliveryStatus.SUCCESS, result.byChannel().get(ChannelKind.WEB).status());
        assertEquals(DeliveryStatus.DISABLED, result.byChannel().get(ChannelKind.EMAIL).status());

        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void markAllReadClearsUnreadInbox() throws Exception {
        notificationCenter.notify(TENANT_B, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(ADMIN_B, "catalog-admin-b@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));
        notificationCenter.notify(TENANT_B, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(ADMIN_B, "catalog-admin-b@example.com", null)),
                "USAGE_EXCEEDED",
                "usage.threshold",
                Map.of("status", "EXCEEDED", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));

        String token = login("catalog-admin-b@example.com");
        mockMvc.perform(put("/api/notifications/read").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").param("unreadOnly", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, ADMIN_B)
                .eq(NotificationEntity::getStatus, "UNREAD"));
        assertEquals(0L, unread);
    }

    @Test
    void markAllReadOnlyClearsSelectedDeliveryMethod() throws Exception {
        notificationService.save(TENANT_B, ADMIN_B, "TEST", "web", "web", "WEB");
        notificationService.save(TENANT_B, ADMIN_B, "TEST", "email", "email", "EMAIL");

        String token = login("catalog-admin-b@example.com");
        mockMvc.perform(put("/api/notifications/read")
                        .param("deliveryMethod", "WEB")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertEquals(0L, notificationMapper.countUnread(ADMIN_B, "WEB"));
        assertEquals(1L, notificationMapper.countUnread(ADMIN_B, "EMAIL"));
    }

    @Test
    void channelSettingsDefaultEnabledWhenMissingRow() throws Exception {
        String token = login("catalog-admin-b@example.com");
        mockMvc.perform(get("/api/notifications/channels/WEB").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
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
