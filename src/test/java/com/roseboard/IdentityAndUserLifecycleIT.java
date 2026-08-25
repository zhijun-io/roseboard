package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import com.roseboard.user.*;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdentityAndUserLifecycleIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");
    @Container static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired CustomerMapper customerMapper;
    @Autowired PasswordEncoder passwordEncoder;

    private final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final String EMAIL = "compatibility@example.com";

    @BeforeEach
    void seed() {
        UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        if (tenantProfileMapper.selectById(profileId) == null) {
            TenantProfileEntity profile = new TenantProfileEntity();
            profile.setId(profileId);
            profile.setCreatedTime(System.currentTimeMillis());
            profile.setName("Compatibility profile");
            tenantProfileMapper.insertProfile(profile.getId(), profile.getCreatedTime(), profile.getName(),
                    profile.getDescription(), profile.getIsDefault(), profile.getIsolatedTbCore(), profile.getIsolatedTbRuleEngine());
        }
        if (tenantMapper.selectById(tenantId) == null) {
            TenantEntity tenant = new TenantEntity();
            tenant.setId(tenantId);
            tenant.setCreatedTime(System.currentTimeMillis());
            tenant.setTenantProfileId(profileId);
            tenant.setTitle("Compatibility tenant");
            tenant.setVersion(1L);
            tenantMapper.insert(tenant);
        }
        if (customerMapper.selectById(customerId) == null) {
            CustomerEntity customer = new CustomerEntity();
            customer.setId(customerId);
            customer.setCreatedTime(System.currentTimeMillis());
            customer.setTenantId(tenantId);
            customer.setTitle("Compatibility customer");
            customer.setVersion(1L);
            customerMapper.insert(customer);
        }
        if (userMapper.selectById(userId) == null) {
            UserEntity user = new UserEntity();
            user.setId(userId);
            user.setCreatedTime(System.currentTimeMillis());
            user.setEmail(EMAIL);
            user.setAuthority(UserAuthority.SYS_ADMIN);
            user.setTenantId(tenantId);
            user.setVersion(1L);
            userMapper.insert(user);
            UserCredentialEntity credentials = new UserCredentialEntity();
            credentials.setId(UUID.randomUUID());
            credentials.setCreatedTime(System.currentTimeMillis());
            credentials.setUserId(userId);
            credentials.setPassword(passwordEncoder.encode("Integration-password1!"));
            credentials.setEnabled(true);
            credentialsMapper.insert(credentials);
        }
    }

    @Test
    void tenantAndCustomerCompatibilityEndpointsUsePostgresData() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/tenants/" + tenantId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(tenantId.toString()));
        mockMvc.perform(get("/api/customers/" + customerId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Compatibility customer"));
    }

    @Test
    void tenantProfileCompatibilityEndpointsUsePostgresData() throws Exception {
        String token = login();
        String payload = "{\"name\":\"API profile\",\"description\":\"integration\",\"profileData\":\"{}\",\"isDefault\":false}";
        String created = mockMvc.perform(post("/api/tenant-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API profile"))
                .andReturn().getResponse().getContentAsString();
        String profileId = new tools.jackson.databind.ObjectMapper().readTree(created).get("id").asText();

        mockMvc.perform(get("/api/tenant-profiles/" + profileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(profileId));
        mockMvc.perform(get("/api/tenant-profiles/" + profileId + "/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("API profile"));
        mockMvc.perform(get("/api/tenant-profiles").param("ids", profileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(profileId));
        mockMvc.perform(put("/api/tenant-profiles/" + profileId + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));
        mockMvc.perform(get("/api/tenant-profiles/default/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(profileId));
        // Default profile cannot be deleted — switch default back to bootstrap then delete.
        mockMvc.perform(put("/api/tenant-profiles/00000000-0000-0000-0000-000000000100/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/tenant-profiles/" + profileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tenantProfileListAliasUsesPostgresData() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/tenant-profiles").param("ids", "00000000-0000-0000-0000-000000000010")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-000000000010"));
    }

    @Test
    void tenantAndCustomerListAliasesUsePostgresData() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/tenants").param("tenantIds", tenantId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tenantId.toString()));
        mockMvc.perform(get("/api/customers").param("customerIds", customerId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(customerId.toString()));
    }


    @Test
    void userInfoCompatibilityEndpointUsesPostgresData() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/user-summaries").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
    @Test
    void userActivationCompatibilityEndpointsExposePersistedCredentialState() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/users/me/token-access").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value(true));
        mockMvc.perform(get("/api/users/" + userId + "/activation-token").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activateToken").value(""));
    }
    @Test
    void databaseContainsDefaultBootstrapIdentityData() {
        UUID profileId = UUID.fromString("00000000-0000-0000-0000-000000000100");
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000103");

        TenantProfileEntity profile = tenantProfileMapper.selectById(profileId);
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        UserEntity user = userMapper.selectById(userId);
        UserCredentialEntity credentials = credentialsMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserCredentialEntity>()
                        .eq(UserCredentialEntity::getUserId, userId));

        org.junit.jupiter.api.Assertions.assertNotNull(profile);
        org.junit.jupiter.api.Assertions.assertNotNull(tenant);
        org.junit.jupiter.api.Assertions.assertNotNull(user);
        org.junit.jupiter.api.Assertions.assertNotNull(credentials);
        org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(credentials.getEnabled()));
        org.junit.jupiter.api.Assertions.assertEquals(UserAuthority.SYS_ADMIN, user.getAuthority());
    }

    @Test
    void userSettingsWriteReadAndDeleteThroughAuthenticatedApi() throws Exception {
        String token = login();
        String auth = "Bearer " + token;

        mockMvc.perform(put("/api/users/me/settings/general").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openedMenuSections\":[\"home\",\"users\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/settings/general").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedMenuSections[1]").value("users"));

        mockMvc.perform(delete("/api/users/me/settings/general").header("Authorization", auth))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/settings/general").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedMenuSections").isEmpty());
    }

    @Test
    void completesAuthenticatedIdentityAndSettingsLifecycle() throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"Integration-password1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        tools.jackson.databind.JsonNode tokens = new tools.jackson.databind.ObjectMapper().readTree(response);
        String token = tokens.get("token").asText();
        String refreshToken = tokens.get("refreshToken").asText();
        String auth = "Bearer " + token;

        mockMvc.perform(get("/api/users/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
        mockMvc.perform(put("/api/users/me/settings/general").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openedMenuSections\":[\"lifecycle\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/settings/general").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedMenuSections[0]").value("lifecycle"));
        mockMvc.perform(post("/api/logout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"Integration-password1!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(response).get("token").asText();
    }
}
