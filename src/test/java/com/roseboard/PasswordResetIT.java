package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.roseboard.user.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PasswordResetIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String EMAIL = "password-reset-integration@example.com";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final String OLD_PASSWORD = "Old-password1!";
    private static final String NEW_PASSWORD = "New-password1!";

    @BeforeEach
    void seedUser() {
        UserEntity existing = userMapper.findByEmail(EMAIL);
        if (existing != null) {
            UserCredentialEntity existingCredentials = credentialsMapper.selectOne(
                    new LambdaQueryWrapper<UserCredentialEntity>().eq(UserCredentialEntity::getUserId, existing.getId()));
            if (existingCredentials != null) credentialsMapper.deleteById(existingCredentials.getId());
            userMapper.deleteById(existing.getId());
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setCreatedTime(System.currentTimeMillis());
        user.setVersion(1L);
        user.setEmail(EMAIL);
        user.setTenantId(TENANT_ID);
        user.setCustomerId(CUSTOMER_ID);
        user.setAuthority(UserAuthority.CUSTOMER_USER);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(user.getId());
        credentials.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        credentials.setEnabled(true);
        credentials.setAdditionalInfo("{}");
        credentials.setFailedLoginAttempts(0);
        credentialsMapper.insert(credentials);
    }

    @Test
    void completesPasswordResetFromEmailRequestToNewLogin() throws Exception {
        mockMvc.perform(post("/api/noauth/resetPasswordByEmail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk());

        UserEntity user = userMapper.findByEmail(EMAIL);
        UserCredentialEntity credentials = credentialsMapper.selectOne(
                new LambdaQueryWrapper<UserCredentialEntity>().eq(UserCredentialEntity::getUserId, user.getId()));
        String resetToken = credentials.getResetToken();

        mockMvc.perform(get("/api/noauth/resetPassword").param("resetToken", resetToken))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login/resetPassword?resetToken=" + resetToken));

        mockMvc.perform(post("/api/noauth/resetPassword")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetToken\":\"" + resetToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/noauth/resetPassword").param("resetToken", resetToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString());

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + OLD_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRequestDoesNotRevealUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/noauth/resetPasswordByEmail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-password-reset@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidResetTokenCannotEnterOrCompleteReset() throws Exception {
        mockMvc.perform(get("/api/noauth/resetPassword").param("resetToken", "invalid-token"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/noauth/resetPassword")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetToken\":\"invalid-token\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
