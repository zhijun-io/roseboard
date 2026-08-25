package com.roseboard.infrastructure.queue;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.queue.stats.QueueStats;
import com.roseboard.queue.stats.QueueStatsService;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QueueAdminHttpIT {
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
        registry.add("roseboard.mqtt.enabled", () -> false);
    }

    private static final String PASSWORD = "Queue-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID SYS_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000703");
    private static final UUID TENANT_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000704");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired QueueStatsService statsService;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        for (UUID userId : java.util.List.of(SYS_ADMIN, TENANT_ADMIN)) {
            userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        tenantMapper.deleteById(TENANT_A);
        if (tenantProfileMapper.selectById(PROFILE_ID) == null) {
            TenantProfileEntity profile = new TenantProfileEntity();
            profile.setId(PROFILE_ID);
            profile.setCreatedTime(System.currentTimeMillis());
            profile.setName("Queue admin profile");
            tenantProfileMapper.insertProfile(PROFILE_ID, profile.getCreatedTime(), profile.getName(),
                    null, false, false, false);
        }
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_A);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(PROFILE_ID);
        tenant.setTitle("Queue tenant");
        tenant.setVersion(1L);
        tenantMapper.insert(tenant);
        insertUser(SYS_ADMIN, null, "queue-sys@example.com", UserAuthority.SYS_ADMIN);
        insertUser(TENANT_ADMIN, TENANT_A, "queue-tenant@example.com", UserAuthority.TENANT_ADMIN);
        statsService.deleteByTenantId(TENANT_A);
        statsService.save(new QueueStats(UUID.randomUUID(), System.currentTimeMillis(),
                TENANT_A, "Main", "core-1"));
    }

    @Test
    void sysAdminCanManageQueuesAndTenantCanRead() throws Exception {
        String sysToken = login("queue-sys@example.com");
        String tenantToken = login("queue-tenant@example.com");
        String body = """
                {
                  "tenantId":"%s",
                  "name":"MainHttp",
                  "topic":"tb_rule_engine.main_http",
                  "pollInterval":25,
                  "partitions":2,
                  "consumerPerPartition":false,
                  "packProcessingTimeout":2000,
                  "submitStrategy":{"type":"SEQUENTIAL_BY_ORIGINATOR","batchSize":0},
                  "processingStrategy":{"type":"SKIP_ALL_FAILURES","retries":0,"failurePercentage":0,
                    "pauseBetweenRetries":0,"maxPauseBetweenRetries":0},
                  "additionalInfo":{}
                }
                """.formatted(TENANT_A);

        String created = mockMvc.perform(post("/api/queues").header("Authorization", bearer(sysToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MainHttp"))
                .andReturn().getResponse().getContentAsString();
        UUID queueId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/api/queues").header("Authorization", bearer(tenantToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/queues").param("pageSize", "10").param("page", "0")
                        .header("Authorization", bearer(tenantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("MainHttp"));

        mockMvc.perform(get("/api/queues?name=MainHttp").header("Authorization", bearer(tenantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(queueId.toString()));

        mockMvc.perform(get("/api/queue-stats").param("pageSize", "10").param("page", "0")
                        .header("Authorization", bearer(tenantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].queueName").value("Main"));

        mockMvc.perform(delete("/api/queues/" + queueId).param("tenantId", TENANT_A.toString())
                        .header("Authorization", bearer(sysToken)))
                .andExpect(status().isOk());
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
        userCredentialMapper.insert(credentials);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
