package com.roseboard.tenant.usage;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.DeviceService;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileMapper;
import com.roseboard.device.profile.DeviceProfileService;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantUsageHttpIT {
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
        registry.add("roseboard.security.mfa.enabled", () -> false);
    }

    private static final String PASSWORD = "Usage-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final UUID SYS_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000904");
    private static final UUID TENANT_ADMIN_A = UUID.fromString("00000000-0000-0000-0000-000000000905");
    private static final UUID TENANT_ADMIN_B = UUID.fromString("00000000-0000-0000-0000-000000000906");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired TenantMapper tenantMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper userCredentialMapper;
    @Autowired DeviceService deviceService;
    @Autowired DeviceProfileService deviceProfileService;
    @Autowired DeviceProfileMapper deviceProfileMapper;
    @Autowired TenantUsageService usageService;
    @Autowired DeviceMapper deviceMapper;
    @Autowired TenantUsageCounterMapper counterMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        for (UUID userId : java.util.List.of(SYS_ADMIN, TENANT_ADMIN_A, TENANT_ADMIN_B)) {
            userCredentialMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        deviceMapper.delete(new LambdaQueryWrapper<DeviceEntity>()
                .in(DeviceEntity::getTenantId, java.util.List.of(TENANT_A, TENANT_B)));
        deviceProfileMapper.delete(new LambdaQueryWrapper<DeviceProfileEntity>()
                .in(DeviceProfileEntity::getTenantId, java.util.List.of(TENANT_A, TENANT_B)));
        counterMapper.delete(new LambdaQueryWrapper<TenantUsageCounterEntity>()
                .in(TenantUsageCounterEntity::getTenantId, java.util.List.of(TENANT_A, TENANT_B)));
        tenantMapper.deleteById(TENANT_A);
        tenantMapper.deleteById(TENANT_B);
        tenantProfileMapper.deleteById(PROFILE_ID);

        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(PROFILE_ID);
        profile.setCreatedTime(System.currentTimeMillis());
        profile.setName("usage-profile");
        profile.setIsDefault(false);
        profile.setIsolatedTbCore(false);
        profile.setIsolatedTbRuleEngine(false);
        ObjectNode configuration = JacksonUtils.objectMapper().createObjectNode();
        configuration.put("maxDevices", 1);
        configuration.put("maxCustomers", 10);
        configuration.put("maxUsers", 10);
        configuration.put("maxTransportMessages", 100);
        configuration.put("warnThreshold", 0.5);
        configuration.put("smsEnabled", false);
        ObjectNode profileData = JacksonUtils.objectMapper().createObjectNode();
        profileData.set("configuration", configuration);
        profileData.set("queueConfiguration", JacksonUtils.objectMapper().createArrayNode());
        profile.setProfileData(profileData);
        tenantProfileMapper.insert(profile);

        insertTenant(TENANT_A, "Usage tenant A");
        insertTenant(TENANT_B, "Usage tenant B");
        deviceProfileService.ensureDefaultForTenant(TENANT_A);
        insertUser(SYS_ADMIN, null, "usage-sys@example.com", UserAuthority.SYS_ADMIN);
        insertUser(TENANT_ADMIN_A, TENANT_A, "usage-a@example.com", UserAuthority.TENANT_ADMIN);
        insertUser(TENANT_ADMIN_B, TENANT_B, "usage-b@example.com", UserAuthority.TENANT_ADMIN);
    }

    @Test
    void tenantAdminSeesEntityCountsAndStatuses() throws Exception {
        String token = login("usage-a@example.com");
        DeviceEntity device = new DeviceEntity();
        device.setTenantId(TENANT_A);
        device.setName("usage-device-1");
        device.setType("sensor");
        deviceService.save(device);

        mockMvc.perform(get("/api/tenants/" + TENANT_A + "/usage").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$.period").value(usageService.currentPeriod()))
                .andExpect(jsonPath("$.items[?(@.key=='maxDevices')].used").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.items[?(@.key=='maxDevices')].limit").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.items[?(@.key=='maxDevices')].status")
                        .value(org.hamcrest.Matchers.contains("EXCEEDED")))
                .andExpect(jsonPath("$.items[?(@.key=='maxSms')].status")
                        .value(org.hamcrest.Matchers.contains("DISABLED")));
    }

    @Test
    void rejectsDeviceOverQuotaAndShowsExceededToSysAdmin() throws Exception {
        DeviceEntity first = new DeviceEntity();
        first.setTenantId(TENANT_A);
        first.setName("usage-device-first");
        first.setType("sensor");
        deviceService.save(first);

        DeviceEntity second = new DeviceEntity();
        second.setTenantId(TENANT_A);
        second.setName("usage-device-second");
        second.setType("sensor");
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> deviceService.save(second));

        String sys = login("usage-sys@example.com");
        mockMvc.perform(get("/api/tenants/" + TENANT_A + "/usage").header("Authorization", bearer(sys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key=='maxDevices')].status")
                        .value(org.hamcrest.Matchers.contains("EXCEEDED")));
    }

    @Test
    void warningStatusAndCrossTenantDenied() throws Exception {
        // maxDevices=2 so one device at 50% warnThreshold is WARNING
        TenantProfileEntity profile = tenantProfileMapper.selectById(PROFILE_ID);
        ObjectNode root = (ObjectNode) profile.getProfileData().deepCopy();
        ObjectNode configuration = (ObjectNode) root.get("configuration");
        configuration.put("maxDevices", 2);
        profile.setProfileData(root);
        tenantProfileMapper.updateById(profile);

        DeviceEntity device = new DeviceEntity();
        device.setTenantId(TENANT_A);
        device.setName("usage-warn-device");
        device.setType("sensor");
        deviceService.save(device);

        String tokenA = login("usage-a@example.com");
        mockMvc.perform(get("/api/tenants/" + TENANT_A + "/usage").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key=='maxDevices')].status")
                        .value(org.hamcrest.Matchers.contains("WARNING")));

        mockMvc.perform(get("/api/tenants/" + TENANT_B + "/usage").header("Authorization", bearer(tokenA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void incrementUpdatesPeriodMetric() throws Exception {
        usageService.increment(TENANT_A, TenantUsageService.MAX_TRANSPORT_MESSAGES, 7);
        String token = login("usage-a@example.com");
        mockMvc.perform(get("/api/tenants/" + TENANT_A + "/usage").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key=='maxTransportMessages')].used")
                        .value(org.hamcrest.Matchers.contains(7)));
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

    private void insertUser(UUID id, UUID tenantId, String email, UserAuthority authority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCreatedTime(System.currentTimeMillis());
        user.setTenantId(tenantId);
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
