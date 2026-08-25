package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityIT {
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

    @Autowired tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String EMAIL = "integration@example.com";
    private static final String PASSWORD = "integration-password";

    @BeforeEach
    void seedUser() {
        UserEntity user = userMapper.findByEmail(EMAIL);
        if (user != null) return;
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setCreatedTime(System.currentTimeMillis());
        user.setVersion(1L);
        user.setEmail(EMAIL);
        user.setAuthority(UserAuthority.SYS_ADMIN);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(user.getId());
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        credentials.setAdditionalInfo("{}");
        userCredentialMapper.insert(credentials);
    }

    @Test
    void loginIssuesJwtAndJwtProtectsCurrentUser() throws Exception {
        String body = "{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
        String token = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(token).get("token").asText();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }
    @Test
    void refreshTokenIssuesReplacementAccessToken() throws Exception {
        String body = "{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(response).get("refreshToken").asText();

        mockMvc.perform(post("/api/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString());
    }

    @Test
    void logoutAcceptsRefreshToken() throws Exception {
        String body = "{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(response).get("refreshToken").asText();
        String accessToken = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(post("/api/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    void mfaSettingsAreAvailableWithJwt() throws Exception {
        String accessToken = accessToken();
        mockMvc.perform(get("/api/users/me/mfa-settings")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void oauth2EndpointIsUnavailableWhenDisabled() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/roseboard"))
                .andExpect(status().isBadRequest());
    }

    private String accessToken() throws Exception {
        String body = "{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }


    @Test
    void unauthenticatedCurrentUserIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }
    @Test
    void ThingsBoardNoAuthCompatibilityEndpointsAreAvailable() throws Exception {
        mockMvc.perform(get("/api/noauth/userPasswordPolicy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumLength").exists());
        mockMvc.perform(get("/api/noauth/activate")
                        .param("activateToken", "missing"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/noauth/resetPassword")
                        .param("resetToken", "missing"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void mfaTotpConfigurationEndpointsWorkWithJwt() throws Exception {
        String accessToken = accessToken();
        String generated = mockMvc.perform(post("/api/users/me/mfa-configuration-drafts")
                        .param("providerType", "TOTP")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerType").value("TOTP"))
                .andExpect(jsonPath("$.secret").isString())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete("/api/users/me/mfa-configurations/TOTP")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "TOTP")
                        .param("verificationCode", "000000")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

}
