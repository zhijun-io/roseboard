package com.roseboard.notification;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.*;
import com.roseboard.infrastructure.notification.model.*;
import com.roseboard.infrastructure.notification.NotificationCenter;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationHttpIT {
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

    private static final String PASSWORD = "Notification-test-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000921");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000922");
    private static final UUID ADMIN_A = UUID.fromString("00000000-0000-0000-0000-000000000923");
    private static final UUID ADMIN_B = UUID.fromString("00000000-0000-0000-0000-000000000924");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired
    NotificationCenter notificationCenter;
    @Autowired NotificationMapper notificationMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        notificationMapper.delete(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getScopeId, TENANT_ID));
        userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .in(UserCredentialEntity::getUserId, ADMIN_A, ADMIN_B));
        userMapper.delete(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getTenantId, TENANT_ID));
        tenantMapper.deleteById(TENANT_ID);
        tenantProfileMapper.deleteById(PROFILE_ID);

        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(PROFILE_ID);
        profile.setCreatedTime(System.currentTimeMillis());
        profile.setName("notification-it-profile");
        profile.setIsDefault(false);
        profile.setIsolatedTbCore(false);
        profile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(profile);

        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(PROFILE_ID);
        tenant.setTitle("Notification IT tenant");
        tenant.setVersion(1L);
        tenantMapper.insert(tenant);

        insertAdmin(ADMIN_A, "notification-admin-a@example.com");
        insertAdmin(ADMIN_B, "notification-admin-b@example.com");
    }

    @Test
    void inboxIsScopedToCurrentUser() throws Exception {
        notificationCenter.notify(TENANT_ID, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(
                        new RecipientRef(ADMIN_A, "notification-admin-a@example.com", null),
                        new RecipientRef(ADMIN_B, "notification-admin-b@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));

        String tokenA = login("notification-admin-a@example.com");
        mockMvc.perform(get("/api/notifications").param("pageSize", "10").param("page", "0")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].recipientId").value(ADMIN_A.toString()))
                .andExpect(jsonPath("$.data[0].status").value("UNREAD"));

        String tokenB = login("notification-admin-b@example.com");
        mockMvc.perform(get("/api/notifications").param("pageSize", "10").param("page", "0")
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].recipientId").value(ADMIN_B.toString()));
    }

    @Test
    void markReadAndReadAll() throws Exception {
        notificationCenter.notify(TENANT_ID, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(ADMIN_A, "notification-admin-a@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));
        notificationCenter.notify(TENANT_ID, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(ADMIN_A, "notification-admin-a@example.com", null)),
                "USAGE_EXCEEDED",
                "usage.threshold",
                Map.of("status", "EXCEEDED", "metricKey", "maxEmails", "period", "2026-01"),
                NotifyOptions.systemNotification()));

        String token = login("notification-admin-a@example.com");
        UUID notificationId = notificationMapper.selectList(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, ADMIN_A)
                .orderByAsc(NotificationEntity::getCreatedTime)).getFirst().getId();

        mockMvc.perform(put("/api/notifications/" + notificationId + "/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").param("unreadOnly", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(put("/api/notifications/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications").param("unreadOnly", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private void insertAdmin(UUID userId, String email) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(TENANT_ID);
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
