package com.roseboard.device.telemetry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
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
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TelemetryAdminHttpIT {
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

    private static final String PASSWORD = "Telemetry-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000603");
    private static final UUID ADMIN_A = UUID.fromString("00000000-0000-0000-0000-000000000604");
    private static final UUID ADMIN_B = UUID.fromString("00000000-0000-0000-0000-000000000605");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    TenantProfileMapper tenantProfileMapper;
    @Autowired
    TenantMapper tenantMapper;
    @Autowired
    UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired
    DeviceMapper deviceMapper;
    @Autowired
    DeviceProfileService profileService;
    @Autowired
    PasswordEncoder passwordEncoder;

    private UUID deviceId;

    @BeforeEach
    void seed() {
        deviceMapper.delete(new LambdaQueryWrapper<DeviceEntity>()
                .in(DeviceEntity::getTenantId, java.util.List.of(TENANT_A, TENANT_B)));
        for (UUID userId : java.util.List.of(ADMIN_A, ADMIN_B)) {
            userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        tenantMapper.deleteById(TENANT_A);
        tenantMapper.deleteById(TENANT_B);
        if (tenantProfileMapper.selectById(PROFILE_ID) == null) {
            TenantProfileEntity profile = new TenantProfileEntity();
            profile.setId(PROFILE_ID);
            profile.setCreatedTime(System.currentTimeMillis());
            profile.setName("Telemetry admin profile");
            tenantProfileMapper.insertProfile(PROFILE_ID, profile.getCreatedTime(), profile.getName(),
                    null, false, false, false);
        }
        insertTenant(TENANT_A, "Telemetry tenant A");
        insertTenant(TENANT_B, "Telemetry tenant B");
        insertUser(ADMIN_A, TENANT_A, "telemetry-admin-a@example.com");
        insertUser(ADMIN_B, TENANT_B, "telemetry-admin-b@example.com");
        DeviceProfileEntity deviceProfile = profileService.ensureDefaultForTenant(TENANT_A);
        DeviceEntity device = new DeviceEntity();
        deviceId = UUID.randomUUID();
        device.setId(deviceId);
        device.setCreatedTime(System.currentTimeMillis());
        device.setTenantId(TENANT_A);
        device.setDeviceProfileId(deviceProfile.getId());
        device.setName("telemetry-admin-device");
        device.setType("sensor");
        device.setVersion(1L);
        deviceMapper.insert(device);
    }

    @Test
    void adminCanWriteReadAndDeleteDeviceTelemetry() throws Exception {
        String tokenA = login("telemetry-admin-a@example.com");
        String tokenB = login("telemetry-admin-b@example.com");
        long ts = System.currentTimeMillis();

        mockMvc.perform(post("/api/plugins/telemetry/DEVICE/" + deviceId + "/timeseries/ANY")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ts\":" + ts + ",\"values\":{\"temperature\":21.5}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/plugins/telemetry/DEVICE/" + deviceId + "/keys/timeseries")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("temperature"));

        mockMvc.perform(get("/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries")
                        .param("keys", "temperature")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature[0].value").value(21.5))
                .andExpect(jsonPath("$.temperature[0].ts").value(ts));

        mockMvc.perform(get("/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries/history")
                        .param("keys", "temperature")
                        .param("startTs", String.valueOf(ts - 1000))
                        .param("endTs", String.valueOf(ts + 1000))
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature[0].value").value(21.5));

        mockMvc.perform(get("/api/plugins/telemetry/DEVICE/" + deviceId + "/values/timeseries")
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/plugins/telemetry/DEVICE/" + deviceId + "/timeseries/delete")
                        .param("keys", "temperature")
                        .param("deleteAllDataForKeys", "true")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature").isNumber());

        mockMvc.perform(get("/api/plugins/telemetry/DEVICE/" + deviceId + "/keys/timeseries")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
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

    private void insertUser(UUID id, UUID tenantId, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setAuthority(UserAuthority.TENANT_ADMIN);
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
