package com.roseboard;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.audit.AuditActions;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.audit.AuditLogEntity;
import com.roseboard.audit.AuditLogMapper;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.AuditTemplate;
import com.roseboard.infrastructure.audit.AuditContext;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditLogIT {
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

    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000610");
    private static final UUID TENANT_USER = UUID.fromString("00000000-0000-0000-0000-000000000611");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final String SYSTEM_EMAIL = "audit-admin@scenario6.test";
    private static final String USER_EMAIL = "audit-user@scenario6.test";
    private static final String PASSWORD = "Scenario6-password1!";

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired TenantMapper tenantMapper;

    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired AuditLogMapper auditLogMapper;
    @Autowired AuditTemplate auditTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedUsersAndClearLogs() {
        auditLogMapper.delete(new LambdaQueryWrapper<AuditLogEntity>());
        credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getUserId, SYSTEM_ADMIN)
                .or().eq(UserCredentialEntity::getUserId, TENANT_USER));
        userMapper.deleteById(SYSTEM_ADMIN);
        userMapper.deleteById(TENANT_USER);
        if (tenantMapper.selectById(TENANT_ID) == null) {
            TenantEntity tenant = new TenantEntity();
            tenant.setId(TENANT_ID);
            tenant.setCreatedTime(System.currentTimeMillis());
            tenant.setTenantProfileId(UUID.fromString("00000000-0000-0000-0000-000000000100"));
            tenant.setTitle("Audit test tenant");
            tenant.setVersion(1L);
            tenantMapper.insert(tenant);
        }
        insertUser(SYSTEM_ADMIN, null, SYSTEM_EMAIL, UserAuthority.SYS_ADMIN);
        insertUser(TENANT_USER, TENANT_ID, USER_EMAIL, UserAuthority.TENANT_ADMIN);
    }

    @Test
    void auditQueriesSupportGlobalUserCustomerEntityActionAndTimeFilters() throws Exception {
        auditTemplate.record(AuditActions.USER_CREATED, SecurityUsers.of(SYSTEM_ADMIN, null, null),
                new AuditTarget(EntityType.USER, TENANT_USER, null, TENANT_ID, null), AuditStatus.SUCCEEDED,
                AuditContext.of(AuditOrigin.HTTP, "audit-global-1", java.util.Map.of()),
                "{\"visible\":\"kept\",\"password\":\"hidden\",\"token\":\"hidden-token\",\"nested\":{\"clientSecret\":\"hidden-secret\"}}",
                null, null);
        auditTemplate.record(AuditActions.USER_DELETED,
                SecurityUsers.of(TENANT_USER, TENANT_ID, CUSTOMER_ID, USER_EMAIL),
                new AuditTarget(EntityType.USER, TENANT_USER, null, TENANT_ID, CUSTOMER_ID), AuditStatus.FAILED,
                AuditContext.empty(),
                "{\"password\":\"hidden\",\"apiKey\":\"hidden-key\",\"failure\":\"invalid password\"}",
                "invalid password", null);
        auditTemplate.record(AuditActions.CUSTOMER_UPDATED, SecurityUsers.of(TENANT_USER, TENANT_ID, CUSTOMER_ID),
                new AuditTarget(EntityType.CUSTOMER, CUSTOMER_ID, null, TENANT_ID, CUSTOMER_ID),
                AuditStatus.SUCCEEDED, AuditContext.empty(), null, null, null);

        String token = login(SYSTEM_EMAIL, PASSWORD);
        String auth = bearer(token);
        long now = System.currentTimeMillis();

        mockMvc.perform(get("/api/audit-logs").param("pageSize", "20").param("page", "0")
                        .param("startTime", Long.toString(now - 60_000))
                        .param("endTime", Long.toString(now + 60_000))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get("/api/audit-logs").param("pageSize", "20").param("page", "0")
                        .param("status", "FAILED")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"));
        mockMvc.perform(get("/api/audit-logs").param("pageSize", "20").param("page", "0")
                        .param("actionTypes", "USER_DELETED")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].failureMessage").value("invalid password"))
                .andExpect(jsonPath("$.data[0].detail.password").doesNotExist())
                .andExpect(jsonPath("$.data[0].detail.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].detail.failure").value("invalid password"));
        mockMvc.perform(get("/api/audit-logs/user/" + TENANT_USER)
                        .param("pageSize", "20").param("page", "0")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/audit-logs/customer/" + CUSTOMER_ID)
                        .param("pageSize", "20").param("page", "0")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/audit-logs/entity/USER/" + TENANT_USER)
                        .param("pageSize", "20").param("page", "0")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.data[0].actorName").value(USER_EMAIL));
    }

    @Test
    void loginPasswordChangeAndLogoutProduceAttributableAuditEvents() throws Exception {
        String token = login(SYSTEM_EMAIL, PASSWORD);
        String response = mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"Scenario6-new-password1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        String newToken = objectMapper.readTree(response).get("token").asText();
        mockMvc.perform(post("/api/logout").header("Authorization", bearer(newToken)))
                .andExpect(status().isOk());

        String query = mockMvc.perform(get("/api/audit-logs/user/" + SYSTEM_ADMIN)
                        .param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(newToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("LOGIN_SUCCESS"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"action\":\"PASSWORD_CHANGED\""));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("\"entityId\":\"" + SYSTEM_ADMIN + "\""));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("PASSWORD_CHANGED"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("LOGOUT"));
    }

    @Test
    void onlyAuthorizedScopeCanReadAuditAndNoMutationRouteExists() throws Exception {
        auditTemplate.record(AuditActions.USER_CREATED, SecurityUsers.of(SYSTEM_ADMIN, null, null),
                AuditTarget.of(EntityType.USER, null), AuditStatus.SUCCEEDED, AuditContext.empty(),
                null, null, null);
        String systemToken = login(SYSTEM_EMAIL, PASSWORD);
        String regularToken = login(USER_EMAIL, PASSWORD);

        mockMvc.perform(get("/api/audit-logs").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(regularToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/audit-logs").header("Authorization", bearer(systemToken)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/audit-logs/00000000-0000-0000-0000-000000000699")
                        .header("Authorization", bearer(systemToken)))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(3, auditLogMapper.selectCount(null));
    }

    @Test
    void failedLoginIsAuditedWithWebRequestContext() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER_EMAIL + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        List<AuditLogEntity> rows = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogEntity>().eq(AuditLogEntity::getAction, AuditActions.LOGIN_FAILED));
        org.junit.jupiter.api.Assertions.assertEquals(1, rows.size());
        AuditLogEntity row = rows.get(0);
        org.junit.jupiter.api.Assertions.assertEquals(AuditStatus.FAILED, row.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(row.getActorName(),
                "failed login has no authenticated actor");
        org.junit.jupiter.api.Assertions.assertEquals(USER_EMAIL, row.getEntityName(),
                "attempted username is kept as entity name");
        org.junit.jupiter.api.Assertions.assertEquals(TENANT_ID, row.getTenantId(),
                "known user login failure carries tenant scope for scoped queries");
        org.junit.jupiter.api.Assertions.assertEquals("Bad credentials", row.getFailureMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(row.getContext(),
                "framework context must attach web request info");
        org.junit.jupiter.api.Assertions.assertEquals("/api/login", row.getContext().path("uri").asString());
        org.junit.jupiter.api.Assertions.assertEquals("POST", row.getContext().path("httpMethod").asString());
        org.junit.jupiter.api.Assertions.assertNotNull(row.getRequestId(),
                "server must generate request id for failed login");
        org.junit.jupiter.api.Assertions.assertEquals(AuditOrigin.HTTP, row.getOrigin());
        org.junit.jupiter.api.Assertions.assertTrue(row.getContext().path("requestBody").isMissingNode(),
                "request body must not enter audit context");
    }

    private void insertUser(UUID id, UUID tenantId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setAuthority(authority);
        user.setVersion(1L);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(id);
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        credentialsMapper.insert(credentials);
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
