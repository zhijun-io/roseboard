package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;

import com.roseboard.audit.AuditLogEntity;
import com.roseboard.audit.AuditLogMapper;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.user.*;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 {@link Audited} 注解切面：成功与失败路径各自落库，演员/租户/客户
 * 来自认证主体，entityId/entityName 由 SpEL 从请求参数提取；非 Web 场景
 * （事件监听）同样生效，主体为空即系统行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({AuditedAspectIT.AuditedTestController.class,
        AuditedAspectIT.AuditedOperations.class,
        AuditedAspectIT.AuditedEventListener.class})
class AuditedAspectIT {

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

    private static final UUID TENANT_USER = UUID.fromString("00000000-0000-0000-0000-000000000621");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final String USER_EMAIL = "aop-user@scenario6.test";
    private static final String PASSWORD = "Scenario6-password1!";

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired TenantMapper tenantMapper;

    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired AuditLogMapper auditLogMapper;
    @Autowired MeterRegistry meterRegistry;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void seedUserAndClearLogs() {
        auditLogMapper.delete(new LambdaQueryWrapper<AuditLogEntity>());
        credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>());
        userMapper.delete(new LambdaQueryWrapper<UserEntity>());
        if (tenantMapper.selectById(TENANT_ID) == null) {
            TenantEntity tenant = new TenantEntity();
            tenant.setId(TENANT_ID);
            tenant.setCreatedTime(System.currentTimeMillis());
            tenant.setTenantProfileId(UUID.fromString("00000000-0000-0000-0000-000000000100"));
            tenant.setTitle("Audited test tenant");
            tenant.setVersion(1L);
            tenantMapper.insert(tenant);
        }
        insertUser(TENANT_USER, TENANT_ID, CUSTOMER_ID, USER_EMAIL, UserAuthority.TENANT_ADMIN);
    }

    @RestController
    static class AuditedTestController {
        @PostMapping("/api/test-audited/create")
        @Audited(action = AuditActions.USER_CREATED, entityType = EntityType.USER,
                entityId = "#body.id", entityName = "#result.name")
        Body create(@RequestBody Body body) {
            return body;
        }

        @PostMapping("/api/test-audited/fail")
        @Audited(action = AuditActions.USER_DELETED, entityType = EntityType.USER, entityId = "#body.id")
        void fail(@RequestBody Body body) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "boom");
        }

        @PostMapping("/api/test-audited/bad-spel")
        @Audited(action = "#missing.field", entityType = EntityType.USER, entityId = "#body.id")
        Body badSpel(@RequestBody Body body) {
            return body;
        }
    }

    record Body(UUID id, String name) {
    }

    /**
     * 非 Web 审计场景：事件监听/定时任务方法本身不经 AOP 代理（若标注在监听方法上不生效），
     * 正确姿势是委托给独立 Bean 的被代理方法——外部调用穿过代理，切面生效；
     * 无认证上下文时主体为空（actorUserId null），即系统行为。
     */
    @Component
    static class AuditedOperations {

        @Audited(action = AuditActions.PASSWORD_RESET, entityType = EntityType.USER,
                entityId = "#userId", entityName = "#email")
        public void passwordReset(UUID userId, String email) {
        }

        @Audited(action = AuditActions.USER_DELETED, entityType = EntityType.USER,
                entityId = "#userId")
        public void deleteUser(UUID userId) {
            throw new IllegalArgumentException("boom");
        }
    }

    @Component
    static class AuditedEventListener {
        private final AuditedOperations auditedOperations;

        AuditedEventListener(AuditedOperations auditedOperations) {
            this.auditedOperations = auditedOperations;
        }

        @EventListener
        @Order(1)
        public void onUserReset(UserResetEvent event) {
            auditedOperations.passwordReset(event.userId(), event.email());
        }

        @EventListener
        @Order(2)
        public void onUserDeletion(UserResetEvent event) {
            auditedOperations.deleteUser(event.userId());
        }

        record UserResetEvent(UUID userId, String email) {
        }
    }

    @Test
    void auditedAnnotationWritesSuccessEntryWithPrincipalAndSpelTarget() throws Exception {
        UUID entityId = UUID.randomUUID();
        String token = login(USER_EMAIL, PASSWORD);

        mockMvc.perform(post("/api/test-audited/create")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", "client-trace-1")
                        .queryParam("audited", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + entityId + "\",\"name\":\"target-name\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isOk());

        List<AuditLogEntity> entries = auditLogMapper.selectList(null);
        assertEquals(2, entries.size(), "LOGIN_SUCCESS + USER_CREATED");
        Counter writes = meterRegistry.find("roseboard.audit.writes").counter();
        assertNotNull(writes, "metrics decorator must register write counter");
        double writeCount = meterRegistry.find("roseboard.audit.writes").counters().stream()
                .mapToDouble(Counter::count).sum();
        assertTrue(writeCount >= entries.size(), "metrics counted writes");
        AuditLogEntity created = entries.stream()
                .filter(e -> AuditActions.USER_CREATED.equals(e.getAction()))
                .findFirst().orElseThrow();
        assertEquals(TENANT_USER, created.getActorUserId());
        assertEquals(TENANT_ID, created.getTenantId());
        assertEquals(CUSTOMER_ID, created.getCustomerId());
        assertEquals(EntityType.USER, created.getEntityType());
        assertEquals(entityId, created.getEntityId());
        assertEquals("target-name", created.getEntityName());
        assertEquals(AuditStatus.SUCCEEDED, created.getStatus());
        assertEquals(AuditOrigin.HTTP, created.getOrigin());
        assertNull(created.getFailureMessage());
        assertNotNull(created.getDurationMs());
        assertTrue(created.getDurationMs() >= 0, "AOP writes durationMs");
        assertNotNull(created.getContext(), "web request context auto-built");
        assertEquals("/api/test-audited/create", created.getContext().get("uri").asText());
        assertEquals("POST", created.getContext().get("httpMethod").asText());
        tools.jackson.databind.JsonNode context = created.getContext();
        assertNotNull(context, "framework context attached for web request");
        assertTrue(context.path("requestBody").isMissingNode(), "request body must not reach audit context");
        assertTrue(context.path("requestParameters").isMissingNode(),
                "request parameters must not reach audit context");
        assertEquals("client-trace-1", created.getRequestId(),
                "client X-Request-Id must pass through as request id");
        AuditLogEntity login = entries.stream()
                .filter(e -> e.getAction().equals(AuditActions.LOGIN_SUCCESS))
                .findFirst().orElseThrow();
        String serverRequestId = login.getRequestId();
        assertNotNull(serverRequestId, "server must generate request id when header absent");
        assertFalse(serverRequestId.equals("client-trace-1"),
                "distinct requests get distinct request ids");
        assertEquals(1, entries.stream().filter(e -> e.getAction().equals(AuditActions.LOGIN_SUCCESS)).count());
    }

    @Test
    void auditedAnnotationWritesFailureEntryAndRethrows() throws Exception {
        UUID entityId = UUID.randomUUID();
        String token = login(USER_EMAIL, PASSWORD);

        mockMvc.perform(post("/api/test-audited/fail")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + entityId + "\"}"))
                .andExpect(status().isBadRequest());

        AuditLogEntity failed = auditLogMapper.selectList(null).stream()
                .filter(e -> e.getAction().equals(AuditActions.USER_DELETED))
                .findFirst().orElseThrow();
        assertEquals(AuditStatus.FAILED, failed.getStatus());
        assertEquals(entityId, failed.getEntityId());
        assertTrue(failed.getFailureMessage().contains("boom"));
        assertNotNull(failed.getDurationMs());
        assertTrue(failed.getDurationMs() >= 0, "AOP failure writes durationMs");
    }

    @Test
    void auditedAnnotationWorksOnEventListenerWithoutSecurityContext() {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        UUID userId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> eventPublisher.publishEvent(
                new AuditedEventListener.UserResetEvent(userId, "listener-target@test")));

        List<AuditLogEntity> entries = auditLogMapper.selectList(null);
        AuditLogEntity reset = entries.stream()
                .filter(e -> AuditActions.PASSWORD_RESET.equals(e.getAction()))
                .findFirst().orElseThrow();
        assertNull(reset.getActorUserId(), "no security context -> system actor (null actorUserId)");
        assertNull(reset.getActorName());
        assertEquals(userId, reset.getEntityId());
        assertEquals("listener-target@test", reset.getEntityName());
        assertEquals(AuditStatus.SUCCEEDED, reset.getStatus());
        assertNull(reset.getContext(), "no web request -> no framework context");
        assertEquals(AuditOrigin.INTERNAL, reset.getOrigin());
        assertNotNull(reset.getDurationMs(), "AOP still records duration");

        AuditLogEntity failed = entries.stream()
                .filter(e -> AuditActions.USER_DELETED.equals(e.getAction()))
                .findFirst().orElseThrow();
        assertEquals(AuditStatus.FAILED, failed.getStatus());
        assertEquals(userId, failed.getEntityId());
        assertTrue(failed.getFailureMessage().contains("boom"));
    }

    @Test
    void auditedSpelFailureDoesNotBlockBusiness() throws Exception {
        UUID entityId = UUID.randomUUID();
        String token = login(USER_EMAIL, PASSWORD);

        mockMvc.perform(post("/api/test-audited/bad-spel")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + entityId + "\",\"name\":\"still-ok\"}"))
                .andExpect(status().isOk());

        AuditLogEntity unresolved = auditLogMapper.selectList(null).stream()
                .filter(e -> "AUDIT_ACTION_UNRESOLVED".equals(e.getAction()))
                .findFirst().orElseThrow();
        assertEquals(entityId, unresolved.getEntityId());
        assertEquals(AuditStatus.SUCCEEDED, unresolved.getStatus());
    }

    private void insertUser(UUID id, UUID tenantId, UUID customerId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setCustomerId(customerId);
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
