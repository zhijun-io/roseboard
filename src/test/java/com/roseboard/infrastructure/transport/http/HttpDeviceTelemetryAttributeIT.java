package com.roseboard.infrastructure.transport.http;

import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.telemetry.TelemetryService;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class HttpDeviceTelemetryAttributeIT extends IntegrationTestBase {
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
    @Autowired TelemetryService telemetryService;
    @Autowired DeviceAttributeService attributeService;

    private UUID deviceId;
    private String deviceToken;

    @BeforeEach
    void seed() {
        seedTenant("http-device-tp-");
        deviceId = createDevice("http-device");
        deviceToken = "http-device-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, deviceToken, null);
    }

    @Test
    void postTelemetryPersistsViaMainQueue() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/http/{token}/telemetry", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temperature\":26.5}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        await(() -> telemetryService.latest(tenantId, deviceId, "temperature") != null, 5);
        assertEquals(26.5, telemetryService.latest(tenantId, deviceId, "temperature").value());
    }

    @Test
    void postTelemetryRejectsInvalidToken() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/http/{token}/telemetry", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temperature\":1}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isUnauthorized());

        assertNull(telemetryService.latest(tenantId, deviceId, "temperature"));
    }

    @Test
    void postAttributesPersistsClientScopeViaMainQueue() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/http/{token}/attributes", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"auto\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        await(() -> attributeService.find(tenantId, deviceId, AttributeScope.CLIENT, new AttributeKey("mode")) != null, 5);
        assertEquals("auto", attributeService.find(tenantId, deviceId, AttributeScope.CLIENT,
                new AttributeKey("mode")).value().value());
    }

    @Test
    void getAttributesReturnsRequestedKeysOnly() throws Exception {
        var principal = credentialsService.authenticateAccessToken(deviceToken);
        attributeService.saveFromDevice(principal, AttributeScope.CLIENT,
                new AttributeKey("visible"), new AttributeValue("yes"));
        attributeService.saveFromDevice(principal, AttributeScope.CLIENT,
                new AttributeKey("hidden"), new AttributeValue("no"));
        attributeService.save(tenantId, deviceId, AttributeScope.SHARED,
                new AttributeKey("sharedKey"), new AttributeValue(42));

        MvcResult mvcResult = mockMvc.perform(get("/api/http/{token}/attributes", deviceToken)
                        .param("clientKeys", "visible")
                        .param("sharedKeys", "sharedKey"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertNotNull(body);
        assertTrue(body.contains("\"visible\":\"yes\""));
        assertFalse(body.contains("hidden"));
        assertTrue(body.contains("\"sharedKey\":42"));
        assertFalse(body.contains("credentials"));
    }
}
