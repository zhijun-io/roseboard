package com.roseboard;

import com.roseboard.infrastructure.notification.channel.HttpSmsClient;
import com.roseboard.setting.AdminSettingService;
import com.roseboard.user.UserAuthority;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import com.roseboard.user.credential.UserCredentialEntity;
import com.roseboard.user.credential.UserCredentialMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SmsMfaIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    private static final HttpServer fakeSms = createFakeSms();
    private static final List<SmsMessage> messages = new CopyOnWriteArrayList<>();
    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
        registry.add("roseboard.security.mfa.enabled", () -> true);
    }

    @AfterAll
    static void stopFakeSms() {
        fakeSms.stop(0);
    }

    private static HttpServer createFakeSms() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", SmsMfaIT::handleMessage);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }


    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    AdminSettingService configManager;

    @Test
    void completesSmsConfigurationChallengeVerificationAndSingleUse() throws Exception {
        String email = "sms-" + UUID.randomUUID() + "@example.com";
        String phone = "+8613800138000";
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
        credentialsMapper.insert(credentials);

        String adminToken = token(login(email, password).andExpect(status().isOk()));
        mockMvc.perform(post("/api/admin-settings/mfa")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enforceTwoFa\":true,\"providers\":[{\"providerType\":\"SMS\",\"verificationCodeLifetime\":300}],\"minVerificationCodeSendPeriod\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].providerType").value("SMS"));
        mockMvc.perform(get("/api/admin-settings/mfa").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].verificationCodeLifetime").value(300));

        mockMvc.perform(put("/api/notifications/platform/sms-settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://127.0.0.1:" + fakeSms.getAddress().getPort()
                                + "\",\"apiKey\":\"test-only-key\"}"))
                .andExpect(status().isOk());
        JsonNode smsSettings = objectMapper.readTree("{\"baseUrl\":\"http://127.0.0.1:" + fakeSms.getAddress().getPort()
                + "\",\"apiKey\":\"test-only-key\"}");
        new HttpSmsClient(smsSettings).sendSms(phone, "您的验证码是 111111，5 分钟内有效");
        assertEquals("111111", latestCode(phone));

        String configToken = token(login(email, password)
                .andExpect(jsonPath("$.refreshToken").doesNotExist()));
        String generated = mockMvc.perform(post("/api/users/me/mfa-configuration-drafts")
                        .param("providerType", "SMS")
                        .param("phoneNumber", phone)
                        .header("Authorization", "Bearer " + configToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerType").value("SMS"))
                .andReturn().getResponse().getContentAsString();
        ObjectNode config = (ObjectNode) objectMapper.readTree(generated);
        config.put("useByDefault", true);

        mockMvc.perform(post("/api/users/me/mfa-configurations/SMS/verification-codes")
                        .param("phoneNumber", phone)
                        .header("Authorization", "Bearer " + configToken))
                .andExpect(status().isOk());
        String setupCode = latestCode(phone);
        mockMvc.perform(post("/api/users/me/mfa-configurations")
                        .param("verificationCode", setupCode)
                        .header("Authorization", "Bearer " + configToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        JsonNode persisted = objectMapper.readTree(mockMvc.perform(get("/api/users/me/mfa-settings")
                        .header("Authorization", "Bearer " + configToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(phone, persisted.path("configs").path("SMS").path("phoneNumber").asText());

        String preToken = token(login(email, password).andExpect(jsonPath("$.refreshToken").doesNotExist()));
        mockMvc.perform(get("/api/login/mfa/providers")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("SMS"));
        mockMvc.perform(post("/api/login/mfa/verification-codes")
                        .param("providerType", "SMS")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk());
        String loginCode = latestCode(phone);
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "SMS")
                        .param("verificationCode", "000000")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest());
        String accessToken = token(mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "SMS")
                        .param("verificationCode", loginCode)
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString()));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/login/mfa/verifications")
                        .param("providerType", "SMS")
                        .param("verificationCode", loginCode)
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest());

        int count = messages.size();
        mockMvc.perform(post("/api/login/mfa/verification-codes")
                        .param("providerType", "SMS")
                        .header("Authorization", "Bearer " + preToken))
                .andExpect(status().isBadRequest());
        assertEquals(count, messages.size());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", email, "password", password))));
    }

    private String token(org.springframework.test.web.servlet.ResultActions response) throws Exception {
        return objectMapper.readTree(response.andReturn().getResponse().getContentAsString()).path("token").asText();
    }

    private static String latestCode(String phone) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            SmsMessage message = messages.get(i);
            if (phone.equals(message.to)) {
                Matcher matcher = CODE.matcher(message.content);
                if (matcher.find()) return matcher.group(1);
            }
        }
        throw new AssertionError("No SMS found for " + phone);
    }

    private static void handleMessage(HttpExchange exchange) throws IOException {
        if (!"test-only-key".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
            exchange.sendResponseHeaders(401, 0); exchange.close(); return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String to = jsonField(body, "to");
        String content = jsonField(body, "content");
        messages.add(new SmsMessage(to, content));
        byte[] response = "{\"messageId\":\"test\",\"status\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(202, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String jsonField(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private record SmsMessage(String to, String content) { }
}
