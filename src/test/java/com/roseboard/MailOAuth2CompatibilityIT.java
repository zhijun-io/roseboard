package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.notification.channel.ChannelConfigService;
import com.roseboard.notification.channel.NotificationChannelConfigEntity;
import com.roseboard.notification.channel.NotificationChannelConfigMapper;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MailOAuth2CompatibilityIT {
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

    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000720");
    private static final String EMAIL = "mail-oauth-admin@scenario.test";
    private static final String PASSWORD = "Mail-oauth-password1!";

    @Autowired MockMvc mockMvc;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired NotificationChannelConfigMapper channelConfigMapper;
    @Autowired ChannelConfigService channelConfigService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedAdminAndMailSettings() {
        credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                .eq(UserCredentialEntity::getUserId, SYSTEM_ADMIN));
        userMapper.deleteById(SYSTEM_ADMIN);
        channelConfigMapper.delete(new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                .isNull(NotificationChannelConfigEntity::getTenantId)
                .eq(NotificationChannelConfigEntity::getChannelKind, "EMAIL"));
        insertAdmin();

        ObjectNode oauth = objectMapper.createObjectNode();
        oauth.put("clientId", "mail-client");
        oauth.put("authUri", "https://provider.example/authorize");
        oauth.put("redirectUri", "https://roseboard.example/api/notifications/platform/channels/email/oauth2/code");
        oauth.putArray("scope").add("offline_access").add("https://mail.example/.default");
        oauth.put("clientSecret", "not-returned");
        oauth.put("tokenUri", "https://provider.example/token");
        ObjectNode config = objectMapper.createObjectNode();
        config.put("smtpHost", "smtp.example.com");
        config.put("smtpPort", 587);
        config.set("oauth2", oauth);
        ObjectNode secrets = objectMapper.createObjectNode();
        secrets.put("clientSecret", "not-returned");
        channelConfigService.savePlatform(ChannelKind.EMAIL,
                new ChannelConfigService.ChannelUpdate(true, null, config, secrets));
    }

    @Test
    void exposesMailOAuth2ProcessingUrlAndAuthorizationRedirect() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/notifications/platform/channels/email/oauth2/login-processing-url")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")));

        mockMvc.perform(get("/api/notifications/platform/channels/email/oauth2/authorize")
                        .param("prevUri", "/settings/outgoing-mail")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(header().string("Set-Cookie", containsString("ROSEBOARD_MAIL_OAUTH2_STATE=")))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("ROSEBOARD_MAIL_OAUTH2_PREV_URI="))));
    }

    @Test
    void rejectsCallbackWithoutMatchingStateCookie() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/notifications/platform/channels/email/oauth2/code")
                        .param("code", "provider-code")
                        .param("state", "wrong-state")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    private void insertAdmin() {
        UserEntity user = new UserEntity();
        user.setId(SYSTEM_ADMIN);
        user.setCreatedTime(System.currentTimeMillis());
        user.setEmail(EMAIL);
        user.setAuthority(UserAuthority.SYS_ADMIN);
        user.setVersion(1L);
        userMapper.insert(user);

        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(System.currentTimeMillis());
        credentials.setUserId(SYSTEM_ADMIN);
        credentials.setPassword(passwordEncoder.encode(PASSWORD));
        credentials.setEnabled(true);
        credentialsMapper.insert(credentials);
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
