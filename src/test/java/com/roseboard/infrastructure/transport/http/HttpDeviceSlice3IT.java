package com.roseboard.infrastructure.transport.http;

import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

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
class HttpDeviceSlice3IT extends IntegrationTestBase {
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
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired DeviceAttributeService attributeService;

    private UUID deviceId;
    private String deviceToken;

    @BeforeEach
    void seed() {
        seedTenant("http-slice3-tp-");
        deviceId = createDevice("http-slice3-device");
        deviceToken = "http-slice3-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @Test
    void attributeUpdatesReturnsSharedChangeWhileWaiting() throws Exception {
        MvcResult pollStarted = mockMvc.perform(get("/api/http/{token}/attributes/updates", deviceToken)
                        .param("timeout", "10000"))
                .andExpect(request().asyncStarted())
                .andReturn();

        attributeService.save(tenantId, deviceId, AttributeScope.SHARED,
                new AttributeKey("target"), new AttributeValue("v1"));

        String body = mockMvc.perform(asyncDispatch(pollStarted))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(body.contains("\"target\":\"v1\""));
    }

    @Test
    void attributeUpdatesTimesOutWhenNoChange() throws Exception {
        MvcResult pollStarted = mockMvc.perform(get("/api/http/{token}/attributes/updates", deviceToken)
                        .param("timeout", "500"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pollStarted)).andExpect(status().isRequestTimeout());
    }

    @Test
    void postDeviceRpcReturnsServerReplyJson() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/{token}/rpc", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"sumOnServer","params":{"a":2,"b":2}}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(4));
    }

    @Test
    void postDeviceRpcRejectsMissingFields() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/http/{token}/rpc", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"ping\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isBadRequest());
    }
}
