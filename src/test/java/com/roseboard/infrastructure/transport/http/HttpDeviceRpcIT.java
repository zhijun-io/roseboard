package com.roseboard.infrastructure.transport.http;

import com.roseboard.user.UserAuthority;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.rpc.DeviceRpcEntity;
import com.roseboard.device.rpc.DeviceRpcMapper;
import com.roseboard.device.rpc.RpcStatus;
import com.roseboard.support.IntegrationTestBase;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class HttpDeviceRpcIT extends IntegrationTestBase {
    private static final String PASSWORD = "Http-rpc-password1!";

    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired DeviceRpcMapper rpcMapper;

    private UUID deviceId;
    private String deviceToken;
    private String tenantAdminToken;

    @BeforeEach
    void seed() throws Exception {
        seedTenant("http-rpc-tp-");
        deviceId = createDevice("http-rpc-device");
        deviceToken = "http-rpc-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
        UUID adminId = UUID.randomUUID();
        insertUser(adminId, tenantId, null, "http-rpc-admin-" + adminId + "@example.com", UserAuthority.TENANT_ADMIN);
        tenantAdminToken = login("http-rpc-admin-" + adminId + "@example.com");
    }

    @Test
    void twowayReturns504WhenDeviceNotWaiting() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/rpc/twoway/{deviceId}", deviceId)
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"ping","params":{},"persistent":false}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isGatewayTimeout());
    }

    @Test
    void twowayDeliversToWaitingDeviceAndReturnsReply() throws Exception {
        MvcResult deviceStarted = mockMvc.perform(get("/api/http/{token}/rpc", deviceToken)
                        .param("timeout", "10000"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult adminStarted = mockMvc.perform(post("/api/rpc/twoway/{deviceId}", deviceId)
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"setGpio","params":{"pin":7},"persistent":false,"timeout":10000}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult deviceResult = mockMvc.perform(asyncDispatch(deviceStarted))
                .andExpect(status().isOk())
                .andReturn();
        String deviceBody = deviceResult.getResponse().getContentAsString();
        assertTrue(deviceBody.contains("\"id\":1"));
        assertTrue(deviceBody.contains("\"method\":\"setGpio\""));

        MvcResult replyStarted = mockMvc.perform(post("/api/http/{token}/rpc/{requestId}", deviceToken, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ok\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(replyStarted)).andExpect(status().isOk());

        mockMvc.perform(asyncDispatch(adminStarted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void persistentRpcDeliveredOnDevicePollAndMarkedSuccessful() throws Exception {
        MvcResult createStarted = mockMvc.perform(post("/api/rpc/twoway/{deviceId}", deviceId)
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"reboot","params":{"delay":1},"persistent":true,"timeout":10000}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        String rpcId = mockMvc.perform(asyncDispatch(createStarted))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replace("\"", "");

        MvcResult pollStarted = mockMvc.perform(get("/api/http/{token}/rpc", deviceToken)
                        .param("timeout", "5000"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String pollBody = mockMvc.perform(asyncDispatch(pollStarted))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(pollBody.contains("\"method\":\"reboot\""));
        assertTrue(pollBody.contains("\"id\":1"));

        MvcResult replyStarted = mockMvc.perform(post("/api/http/{token}/rpc/{requestId}", deviceToken, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(replyStarted)).andExpect(status().isOk());

        DeviceRpcEntity rpc = rpcMapper.selectById(UUID.fromString(rpcId));
        assertEquals(RpcStatus.SUCCESSFUL.name(), rpc.getStatus());
        assertTrue(rpc.getResponse().get("done").asBoolean());
    }

    private void insertUser(UUID id, UUID tenantId, UUID customerId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setCustomerId(customerId);
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
        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
