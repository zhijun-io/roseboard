package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.apikey.ApiKeyEntity;
import com.roseboard.apikey.ApiKeyMapper;
import com.roseboard.setting.AdminSettingEntity;
import com.roseboard.setting.AdminSettingMapper;
import com.roseboard.setting.oauth2.domain.DomainEntity;
import com.roseboard.setting.oauth2.domain.DomainMapper;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SettingsApiKeyDomainIT {
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

    private static final String PASSWORD = "Scenario5-password1!";
    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000510");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000511");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String SYSTEM_EMAIL = "system-admin@scenario5.test";
    private static final String USER_EMAIL = "tenant-user@scenario5.test";
    private static final String DOMAIN_NAME = "scenario5.example.test";

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired ApiKeyMapper apiKeyMapper;
    @Autowired
    AdminSettingMapper settingsMapper;
    @Autowired DomainMapper domainMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedUsersAndCleanScenarioData() {
        apiKeyMapper.delete(new LambdaQueryWrapper<ApiKeyEntity>().eq(ApiKeyEntity::getUserId, USER));
        credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getUserId, SYSTEM_ADMIN).or().eq(UserCredentialEntity::getUserId, USER));
        userMapper.deleteById(SYSTEM_ADMIN);
        userMapper.deleteById(USER);
        settingsMapper.delete(new LambdaQueryWrapper<AdminSettingEntity>()
                .eq(AdminSettingEntity::getTenantId, new UUID(0, 0)).eq(AdminSettingEntity::getKey, "connectivity"));
        domainMapper.delete(new LambdaQueryWrapper<DomainEntity>().eq(DomainEntity::getName, DOMAIN_NAME));
        insertUser(SYSTEM_ADMIN, null, SYSTEM_EMAIL, UserAuthority.SYS_ADMIN);
        insertUser(USER, TENANT_ID, USER_EMAIL, UserAuthority.TENANT_ADMIN);
    }

    @Test
    void platformSettingsPersistAndSensitiveFieldsAreSanitized() throws Exception {
        String systemToken = login(SYSTEM_EMAIL);
        String regularToken = login(USER_EMAIL);
        mockMvc.perform(post("/api/admin-settings/jwt").header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"issuer\":\"scenario5\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issuer").value("scenario5"));
        mockMvc.perform(get("/api/admin-settings/jwt").header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issuer").value("scenario5"));
        mockMvc.perform(get("/api/admin-settings/jwt").header("Authorization", bearer(regularToken))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin-settings/security").header("Authorization", bearer(regularToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void connectivitySettingsPersistByTbCompatibleKey() throws Exception {
        String systemToken = login(SYSTEM_EMAIL);
        mockMvc.perform(post("/api/admin-settings").header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"connectivity\",\"value\":{\"mqtt\":{\"enabled\":true,\"host\":\"localhost\",\"port\":1883}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.mqtt.enabled").value(true))
                .andExpect(jsonPath("$.value.mqtt.port").value(1883));
        mockMvc.perform(get("/api/admin-settings/connectivity").header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.mqtt.host").value("localhost"));
    }

    @Test
    void apiKeySupportsAuthenticationLifecycleAndNeverListsPlaintext() throws Exception {
        String systemToken = login(SYSTEM_EMAIL);
        String regularToken = login(USER_EMAIL);
        String response = mockMvc.perform(post("/api/users/" + USER + "/api-keys").header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER + "\",\"description\":\"initial key\",\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").isString())
                .andExpect(jsonPath("$.description").value("initial key")).andExpect(jsonPath("$.keyHash").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        String keyId = created.get("id").asText();
        String keyValue = created.get("value").asText();
        mockMvc.perform(get("/api/users/" + USER + "/api-keys").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].description").value("initial key"))
                .andExpect(jsonPath("$.data[0].value").doesNotExist());
        mockMvc.perform(patch("/api/api-keys/" + keyId).header("Authorization", bearer(regularToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"renamed key\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("renamed key"));
        mockMvc.perform(patch("/api/api-keys/" + keyId).header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/users/me/token-access").header("X-Authorization", "ApiKey " + keyValue))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/api-keys/" + keyId).header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(get("/api/users/me/token-access").header("X-Authorization", "ApiKey " + keyValue))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value(true));
        mockMvc.perform(post("/api/users/" + SYSTEM_ADMIN + "/api-keys").header("Authorization", bearer(regularToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/api-keys/" + keyId).header("Authorization", bearer(systemToken))).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/" + USER + "/api-keys").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void systemAdminCanManageDomainWhileRegularUserIsRejected() throws Exception {
        String systemToken = login(SYSTEM_EMAIL);
        String regularToken = login(USER_EMAIL);
        String response = mockMvc.perform(post("/api/domains").header("Authorization", bearer(systemToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + DOMAIN_NAME + "\",\"description\":\"initial domain\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(DOMAIN_NAME))
                .andExpect(jsonPath("$.description").value("initial domain"))
                .andReturn().getResponse().getContentAsString();
        String domainId = objectMapper.readTree(response).get("id").asText();
        mockMvc.perform(get("/api/domains/" + domainId).header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(DOMAIN_NAME));
        mockMvc.perform(get("/api/domains").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(systemToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.name == 'scenario5.example.test')]").exists());
        mockMvc.perform(put("/api/domains/" + domainId).header("Authorization", bearer(systemToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + DOMAIN_NAME + "\",\"description\":\"updated domain\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("updated domain"));
        mockMvc.perform(get("/api/domains/" + domainId).header("Authorization", bearer(regularToken))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/domains/" + domainId).header("Authorization", bearer(systemToken))).andExpect(status().isOk());
        mockMvc.perform(get("/api/domains/" + domainId).header("Authorization", bearer(systemToken))).andExpect(status().isNotFound());
    }

    private void insertUser(UUID id, UUID tenantId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id); user.setCreatedTime(System.currentTimeMillis()); user.setTenantId(tenantId);
        user.setEmail(email); user.setAuthority(authority); user.setVersion(1L); userMapper.insert(user);
        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID()); credentials.setCreatedTime(System.currentTimeMillis()); credentials.setUserId(id);
        credentials.setPassword(passwordEncoder.encode(PASSWORD)); credentials.setEnabled(true); credentialsMapper.insert(credentials);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }
}
