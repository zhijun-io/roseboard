package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialMapper;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DeviceIT {
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
        registry.add("roseboard.mqtt.enabled", () -> false);
    }

    private static final String PASSWORD = "Device-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final UUID CUSTOMER_A = UUID.fromString("00000000-0000-0000-0000-000000000504");
    private static final UUID CUSTOMER_B = UUID.fromString("00000000-0000-0000-0000-000000000505");
    private static final UUID ADMIN_A = UUID.fromString("00000000-0000-0000-0000-000000000506");
    private static final UUID ADMIN_B = UUID.fromString("00000000-0000-0000-0000-000000000507");
    private static final UUID CUSTOMER_USER_B = UUID.fromString("00000000-0000-0000-0000-000000000508");
    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000509");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired CustomerMapper customerMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired
    DeviceCredentialMapper deviceCredentialMapper;
    @Autowired
    DeviceCredentialService credentialsService;
    @Autowired DeviceProfileMapper deviceProfileMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        deviceCredentialMapper.delete(new LambdaQueryWrapper<DeviceCredentialEntity>()
                .in(DeviceCredentialEntity::getDeviceId, List.of(TENANT_A, TENANT_B)));
        deviceMapper.delete(new LambdaQueryWrapper<DeviceEntity>()
                .in(DeviceEntity::getTenantId, List.of(TENANT_A, TENANT_B)));
        deviceProfileMapper.delete(new LambdaQueryWrapper<DeviceProfileEntity>()
                .in(DeviceProfileEntity::getTenantId, List.of(TENANT_A, TENANT_B)));
        for (UUID userId : List.of(ADMIN_A, ADMIN_B, CUSTOMER_USER_B, SYSTEM_ADMIN)) {
            userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        customerMapper.deleteById(CUSTOMER_A);
        customerMapper.deleteById(CUSTOMER_B);
        tenantMapper.deleteById(TENANT_A);
        tenantMapper.deleteById(TENANT_B);

        if (tenantProfileMapper.selectById(PROFILE_ID) == null) {
            TenantProfileEntity profile = new TenantProfileEntity();
            profile.setId(PROFILE_ID);
            profile.setCreatedTime(System.currentTimeMillis());
            profile.setName("Device profile");
            tenantProfileMapper.insertProfile(PROFILE_ID, profile.getCreatedTime(), profile.getName(),
                    null, false, false, false);
        }
        insertTenant(TENANT_A, "Device tenant A");
        insertTenant(TENANT_B, "Device tenant B");
        insertCustomer(CUSTOMER_A, TENANT_A, "Device customer A");
        insertCustomer(CUSTOMER_B, TENANT_B, "Device customer B");
        insertUser(ADMIN_A, TENANT_A, null, "device-admin-a@example.com", UserAuthority.TENANT_ADMIN);
        insertUser(ADMIN_B, TENANT_B, null, "device-admin-b@example.com", UserAuthority.TENANT_ADMIN);
        insertUser(CUSTOMER_USER_B, TENANT_B, CUSTOMER_B, "device-customer-b@example.com", UserAuthority.CUSTOMER_USER);
        insertUser(SYSTEM_ADMIN, null, null, "device-system@example.com", UserAuthority.SYS_ADMIN);
    }

    @Test
    void deviceScopeAndCredentialsAreEnforced() throws Exception {
        String tenantAdminA = login("device-admin-a@example.com");
        String tenantAdminB = login("device-admin-b@example.com");
        String customerUserB = login("device-customer-b@example.com");
        String systemAdmin = login("device-system@example.com");
        String body = "{\"tenantId\":\"" + TENANT_A + "\",\"customerId\":\"" + CUSTOMER_A
                + "\",\"name\":\"temperature sensor\",\"type\":\"sensor\","
                + "\"additionalInfo\":{\"site\":\"lab\"}}";

        String response = mockMvc.perform(post("/api/devices").header("Authorization", bearer(systemAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.additionalInfo.site").value("lab"))
                .andReturn().getResponse().getContentAsString();
        UUID deviceId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        mockMvc.perform(get("/api/devices/" + deviceId).header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/" + deviceId).header("Authorization", bearer(tenantAdminB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/" + deviceId).header("Authorization", bearer(customerUserB)))
                .andExpect(status().isNotFound());

        String credentialsResponse = mockMvc.perform(post("/api/devices/" + deviceId + "/credentials")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialsValue").isNotEmpty())
                .andExpect(jsonPath("$.credentialsValueHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(credentialsResponse).get("credentialsValue").asText();
        assertNotNull(credentialsService.authenticateAccessToken(token));

        mockMvc.perform(get("/api/devices/" + deviceId + "/credentials")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialsValue").doesNotExist());
        mockMvc.perform(delete("/api/devices/" + deviceId + "/credentials")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isNoContent());
        assertNull(credentialsService.authenticateAccessToken(token));
        assertFalse(Boolean.TRUE.equals(deviceCredentialMapper.selectOne(
                new LambdaQueryWrapper<DeviceCredentialEntity>().eq(DeviceCredentialEntity::getDeviceId, deviceId))
                .getEnabled()));
    }

    @Test
    void assignUnassignAndTypeFilterWork() throws Exception {
        String tenantAdminA = login("device-admin-a@example.com");
        String systemAdmin = login("device-system@example.com");
        String body = "{\"tenantId\":\"" + TENANT_A + "\",\"name\":\"filter sensor\",\"type\":\"thermostat\"}";

        String response = mockMvc.perform(post("/api/devices").header("Authorization", bearer(systemAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID deviceId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        UUID deviceProfileId = UUID.fromString(objectMapper.readTree(response).get("deviceProfileId").asText());

        mockMvc.perform(put("/api/devices/" + deviceId + "/customer")
                        .header("Authorization", bearer(tenantAdminA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + CUSTOMER_A + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_A.toString()));

        mockMvc.perform(get("/api/devices").param("pageSize", "10").param("page", "0")
                        .param("type", "thermostat")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(deviceId.toString()));

        mockMvc.perform(get("/api/devices").param("pageSize", "10").param("page", "0")
                        .param("deviceProfileId", deviceProfileId.toString())
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(deviceId.toString()));

        mockMvc.perform(get("/api/devices").param("pageSize", "10").param("page", "0")
                        .param("deviceProfileId", UUID.randomUUID().toString())
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/devices").param("pageSize", "10").param("page", "0")
                        .param("type", "gateway")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(delete("/api/devices/" + deviceId + "/customer")
                        .header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/devices/" + deviceId).header("Authorization", bearer(tenantAdminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        mockMvc.perform(put("/api/devices/" + deviceId).header("Authorization", bearer(tenantAdminA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"filter sensor\","
                                + "\"firmwareId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    private void insertTenant(UUID id, String title) {
                TenantEntity tenant = new TenantEntity();
                tenant.setId(id);
                tenant.setCreatedTime(System.currentTimeMillis());
                tenant.setTenantProfileId(PROFILE_ID);
                tenant.setTitle(title);
                tenant.setVersion(1L);
                tenantMapper.insert(tenant);
            }

    private void insertCustomer(UUID id, UUID tenantId, String title) {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(id);
        customer.setCreatedTime(System.currentTimeMillis());
        customer.setTenantId(tenantId);
        customer.setTitle(title);
        customer.setVersion(1L);
        customerMapper.insert(customer);
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
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
