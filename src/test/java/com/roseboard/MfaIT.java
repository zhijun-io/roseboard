package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.audit.AuditActions;
import com.roseboard.audit.AuditLogEntity;
import com.roseboard.audit.AuditLogMapper;
import com.roseboard.setting.AdminSettingService;
import com.roseboard.setting.mfa.MfaSetting;
import com.roseboard.user.*;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MfaIT {
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
        registry.add("roseboard.security.mfa.enabled", () -> true);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired
    AuditLogMapper auditLogMapper;
    @Autowired
    AdminSettingService adminSettingService;
    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetPlatformMfaPolicy() {
        adminSettingService.saveMfaSettings(new MfaSetting());
    }

    @Test
    void enforcedTotpMfaFlowsFromConfigurationToVerifiedLogin() throws Exception {
        String email = "mfa-" + UUID.randomUUID() + "@example.com";
        String password = "integration-password";
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setCreatedTime(System.currentTimeMillis());
        user.setVersion(1L);
        user.setEmail(email);
        user.setAuthority(UserAuthority.SYS_ADMIN);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(user.getId());
        credentials.setPassword(passwordEncoder.encode(password));
        credentials.setEnabled(true);
        credentials.setAdditionalInfo("{}");
        userCredentialMapper.insert(credentials);

        String adminLogin = login(email, password)
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        String adminToken = objectMapper.readTree(adminLogin).get("token").asText();
        mockMvc.perform(post("/api/admin-settings/mfa")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforceTwoFa\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforceTwoFa").value(true));
        mockMvc.perform(get("/api/admin-settings/mfa").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforceTwoFa").value(true));


        String configurationToken = login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertLoginSuccessCount(user.getId(), 1,
                "password ok with MFA configuration pending must not audit LOGIN_SUCCESS");
        String configAccessToken = objectMapper.readTree(configurationToken).get("token").asText();
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + configAccessToken))
                .andExpect(status().isForbidden());

        String generated = mockMvc.perform(post("/api/users/me/mfa-configuration-drafts")
                        .param("providerType", "TOTP")
                        .header("Authorization", "Bearer " + configAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerType").value("TOTP"))
                .andReturn().getResponse().getContentAsString();
        ObjectNode config = (ObjectNode) objectMapper.readTree(generated);
        config.put("useByDefault", true);
        String secret = config.get("secret").asText();

        mockMvc.perform(post("/api/users/me/mfa-configurations")
                        .param("verificationCode", totp(secret))
                        .header("Authorization", "Bearer " + configAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        String fullToken = mockMvc.perform(post("/api/login/mfa/session")
                        .header("Authorization", "Bearer " + configAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + objectMapper.readTree(fullToken).get("token").asText()))
                .andExpect(status().isOk());

        String preVerification = login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertLoginSuccessCount(user.getId(), 2,
                "password ok with MFA verification pending must not add LOGIN_SUCCESS");
        String preToken = objectMapper.readTree(preVerification).get("token").asText();
        mockMvc.perform(get("/api/login/mfa/providers")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("TOTP"));
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "EMAIL")
                        .param("verificationCode", "000000")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("MFA provider is unavailable: EMAIL"));
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "TOTP")
                        .param("verificationCode", "000000")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification code is incorrect"));


        String verified = mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "TOTP")
                        .param("verificationCode", totp(secret))
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "TOTP")
                        .param("verificationCode", totp(secret))
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("MFA pre-verification token already used"));
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + objectMapper.readTree(verified).get("token").asText()))
                .andExpect(status().isOk());

        AuditLogEntity audit = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getActorUserId, user.getId())
                .eq(AuditLogEntity::getAction, "MFA_VERIFIED")
                .orderByDesc(AuditLogEntity::getOccurredAt)
                .last("limit 1"));
        org.junit.jupiter.api.Assertions.assertNotNull(audit);
        org.junit.jupiter.api.Assertions.assertEquals(com.roseboard.infrastructure.audit.event.AuditStatus.SUCCEEDED,
                audit.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(objectMapper.readTree("{\"providerType\":\"TOTP\"}"), audit.getDetail());
        assertLoginSuccessCount(user.getId(), 2,
                "full session is established via MFA session + MFA verified, not premature password login");
        org.junit.jupiter.api.Assertions.assertEquals(1, auditLogMapper.selectCount(
                new LambdaQueryWrapper<AuditLogEntity>()
                        .eq(AuditLogEntity::getActorUserId, user.getId())
                        .eq(AuditLogEntity::getAction, AuditActions.MFA_VERIFIED)));
    }

    private void assertLoginSuccessCount(UUID userId, long expected, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, auditLogMapper.selectCount(
                new LambdaQueryWrapper<AuditLogEntity>()
                        .eq(AuditLogEntity::getActorUserId, userId)
                        .eq(AuditLogEntity::getAction, AuditActions.LOGIN_SUCCESS)), message);
    }


    @Test
    void backupCodeIsOneTimeAndProviderDeletionChangesNextLoginState() throws Exception {
        String email = "mfa-backup-" + UUID.randomUUID() + "@example.com";
        String password = "integration-password";
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setCreatedTime(System.currentTimeMillis());
        user.setVersion(1L);
        user.setEmail(email);
        user.setAuthority(UserAuthority.SYS_ADMIN);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(user.getId());
        credentials.setPassword(passwordEncoder.encode(password));
        credentials.setEnabled(true);
        credentials.setAdditionalInfo("{}");
        userCredentialMapper.insert(credentials);

        String adminToken = objectMapper.readTree(login(email, password)
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/admin-settings/mfa")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforceTwoFa\":true}"))
                .andExpect(status().isOk());

        String configurationToken = objectMapper.readTree(login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        String backup = mockMvc.perform(post("/api/users/me/mfa-configuration-drafts")
                        .param("providerType", "BACKUP_CODE")
                        .header("Authorization", "Bearer " + configurationToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode backupConfig = (ObjectNode) objectMapper.readTree(backup);
        backupConfig.put("useByDefault", true);
        String backupCode = backupConfig.path("codes").path(0).asText();
        mockMvc.perform(post("/api/users/me/mfa-configurations")
                        .header("Authorization", "Bearer " + configurationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(backupConfig)))
                .andExpect(status().isOk());

        String preToken = objectMapper.readTree(login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        String verified = mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "BACKUP_CODE")
                        .param("verificationCode", backupCode)
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + objectMapper.readTree(verified).get("token").asText()))
                .andExpect(status().isOk());

        String replayToken = objectMapper.readTree(login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "BACKUP_CODE")
                        .param("verificationCode", backupCode)
                        .header("Authorization", "Bearer " + replayToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification code is incorrect"));

        mockMvc.perform(delete("/api/users/me/mfa-configurations/BACKUP_CODE")
                        .header("Authorization", "Bearer " + configurationToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/mfa-settings")
                        .header("Authorization", "Bearer " + configurationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs.BACKUP_CODE").doesNotExist());

        String regenerated = mockMvc.perform(post("/api/users/me/mfa-configuration-drafts")
                        .param("providerType", "BACKUP_CODE")
                        .header("Authorization", "Bearer " + configurationToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode regeneratedConfig = (ObjectNode) objectMapper.readTree(regenerated);
        regeneratedConfig.put("useByDefault", true);
        mockMvc.perform(post("/api/users/me/mfa-configurations")
                        .header("Authorization", "Bearer " + configurationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regeneratedConfig)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/mfa-settings")
                        .header("Authorization", "Bearer " + configurationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs.BACKUP_CODE.codes").isArray());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "username", email, "password", password))));
    }

    private String totp(String secret) throws Exception {
        byte[] key = Base64.getUrlDecoder().decode(secret);
        long counter = System.currentTimeMillis() / 30_000L;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0xf;
        int value = ((hash[offset] & 0x7f) << 24 | (hash[offset + 1] & 0xff) << 16
                | (hash[offset + 2] & 0xff) << 8 | (hash[offset + 3] & 0xff)) % 1_000_000;
        return String.format("%06d", value);
    }
}
