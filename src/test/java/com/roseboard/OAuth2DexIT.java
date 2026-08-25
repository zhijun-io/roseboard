package com.roseboard;

import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "roseboard.security.oauth2.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class OAuth2DexIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("roseboard").withUsername("roseboard").withPassword("roseboard");

    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> dex = new GenericContainer<>("ghcr.io/dexidp/dex:v2.41.1")
            .withCopyFileToContainer(MountableFile.forClasspathResource("dex/config.yaml"), "/etc/dex/config.yaml")
            .withCommand("dex", "serve", "/etc/dex/config.yaml")
            .withExposedPorts(5556)
            .waitingFor(Wait.forHttp("/dex/.well-known/openid-configuration").forPort(5556));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=roseboard");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired
    OAuth2ClientMapper clientMapper;

    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private static final String EMAIL = "oauth2-integration@example.com";
    private static final String PASSWORD = "integration-password";

    private static final UUID CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void seedDexClient() {
        if (clientMapper.selectById(CLIENT_ID) != null) return;
        OAuth2ClientEntity client = new OAuth2ClientEntity();
        client.setId(CLIENT_ID);
        client.setCreatedTime(System.currentTimeMillis());
        client.setTitle("Dex integration");
        client.setTenantId(new UUID(0, 0));
        UserEntity user = userMapper.findByEmail(EMAIL);
        if (user == null) {
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
            credentialsMapper.insert(credentials);
        }
        client.setClientId("roseboard-dex");
        client.setClientSecret("roseboard-dex-secret");
        client.setAuthorizationUri("http://127.0.0.1:" + dex.getMappedPort(5556) + "/dex/auth");
        client.setAccessTokenUri("http://127.0.0.1:" + dex.getMappedPort(5556) + "/dex/token");
        client.setUserInfoUri("http://127.0.0.1:" + dex.getMappedPort(5556) + "/dex/userinfo");
        client.setScope("openid profile email");
        client.setRedirectUri("http://127.0.0.1:8080/login/oauth2/code/roseboard");
        client.setUserNameAttributeName("email");
        client.setAllowUserCreation(false);
        client.setActivateUser(true);
        client.setType("OIDC");
        clientMapper.insert(client);
    }

    private String accessToken() throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void redirectsToDexAuthorizationEndpointUsingPersistedClient() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/" + CLIENT_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("127.0.0.1")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("client_id=roseboard-dex")));
    }

    @Test
    void exposesThingsBoardCompatibleLoginProcessingUrl() throws Exception {
        mockMvc.perform(get("/api/oauth2/loginProcessingUrl")
                        .header("Authorization", "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("\"/login/oauth2/code/*\""));
    }
    @Test
    void exposesOAuth2ClientsAtTopLevelResourcePath() throws Exception {
        String authorization = "Bearer " + accessToken();
        mockMvc.perform(get("/api/oauth2-clients/" + CLIENT_ID)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id")
                        .value(CLIENT_ID.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.clientSecret")
                        .doesNotExist());
        mockMvc.perform(get("/api/oauth2-clients")
                        .param("pageSize", "10")
                        .param("page", "0")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].id")
                        .value(CLIENT_ID.toString()));
    }


    @Test
    void exposesCompatiblePublicOAuth2ClientInfo() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/noauth/oauth2Clients"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].name")
                        .value("Dex integration"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].icon").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].url")
                        .value("/oauth2/authorization/" + CLIENT_ID));
    }
}
