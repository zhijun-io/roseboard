package com.roseboard.tenant;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantProfileHttpIT {
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

    private static final String PASSWORD = "TenantProfile-password1!";
    private static final UUID SYS_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID TENANT_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000803");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired
    TenantProfileMapper tenantProfileMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        for (UUID userId : java.util.List.of(SYS_ADMIN, TENANT_ADMIN)) {
            userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        tenantMapper.deleteById(TENANT_A);
        insertUser(SYS_ADMIN, null, "tp-sys@example.com", UserAuthority.SYS_ADMIN);
        // Ensure a dedicated non-default profile exists for tenant binding tests
        TenantProfileEntity bindProfile = tenantProfileMapper.selectById(
                UUID.fromString("00000000-0000-0000-0000-000000000804"));
        if (bindProfile == null) {
            tenantProfileMapper.insertProfile(
                    UUID.fromString("00000000-0000-0000-0000-000000000804"),
                    System.currentTimeMillis(), "tp-bind-profile", null, false, false, false);
        }
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_A);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(UUID.fromString("00000000-0000-0000-0000-000000000804"));
        tenant.setTitle("TP tenant");
        tenant.setVersion(1L);
        if (tenantMapper.selectById(TENANT_A) == null) {
            tenantMapper.insert(tenant);
        }
        insertUser(TENANT_ADMIN, TENANT_A, "tp-tenant@example.com", UserAuthority.TENANT_ADMIN);
    }

    @Test
    void persistsTbShapedProfileDataAndHidesItOnInfo() throws Exception {
        String token = login("tp-sys@example.com");
        String payload = """
                {
                  "name": "tp-roundtrip",
                  "description": "persist",
                  "isDefault": false,
                  "profileData": {
                    "configuration": {
                      "maxDevices": 10,
                      "maxUsers": 5,
                      "smsEnabled": true,
                      "unknownTbField": 42
                    },
                    "queueConfiguration": []
                  }
                }
                """;
        String created = mockMvc.perform(post("/api/tenant-profiles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.configuration.maxDevices").value(10))
                .andExpect(jsonPath("$.profileData.configuration.unknownTbField").value(42))
                .andReturn().getResponse().getContentAsString();
        String profileId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/tenant-profiles/" + profileId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.configuration.maxDevices").value(10))
                .andExpect(jsonPath("$.profileData.configuration.unknownTbField").value(42))
                .andExpect(jsonPath("$.profileData.queueConfiguration").isArray());

        mockMvc.perform(get("/api/tenant-profiles/" + profileId + "/summary").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId))
                .andExpect(jsonPath("$.name").value("tp-roundtrip"))
                .andExpect(jsonPath("$.profileData").doesNotExist())
                .andExpect(jsonPath("$.isDefault").doesNotExist());

        mockMvc.perform(post("/api/tenant-profiles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tp-empty-data\",\"isDefault\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileData.configuration").exists())
                .andExpect(jsonPath("$.profileData.queueConfiguration").isArray());
    }

    @Test
    void guardsDefaultAndReferencedDeletesAndSwitchesDefault() throws Exception {
        String token = login("tp-sys@example.com");
        String a = createProfile(token, "tp-guard-a");
        String b = createProfile(token, "tp-guard-b");

        mockMvc.perform(put("/api/tenant-profiles/" + a + "/default")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(delete("/api/tenant-profiles/" + a).header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/tenant-profiles/" + b + "/default")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mockMvc.perform(get("/api/tenant-profiles/" + a).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(delete("/api/tenant-profiles/" + a).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String referenced = UUID.fromString("00000000-0000-0000-0000-000000000804").toString();
        mockMvc.perform(delete("/api/tenant-profiles/" + referenced).header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    @Test
    void tenantAdminCannotWriteTenantProfile() throws Exception {
        String token = login("tp-tenant@example.com");
        mockMvc.perform(post("/api/tenant-profiles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"denied\",\"isDefault\":false}"))
                .andExpect(status().isForbidden());
    }

    private String createProfile(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/tenant-profiles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"isDefault\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
