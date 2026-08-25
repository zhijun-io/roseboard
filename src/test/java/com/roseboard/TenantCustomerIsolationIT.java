package com.roseboard;

import com.roseboard.user.UserAuthority;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
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

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantCustomerIsolationIT {
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

    private static final String PASSWORD = "Scenario4-password1!";
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000411");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000412");
    private static final UUID CUSTOMER_A = UUID.fromString("00000000-0000-0000-0000-000000000413");
    private static final UUID CUSTOMER_B = UUID.fromString("00000000-0000-0000-0000-000000000414");
    private static final UUID TENANT_ADMIN_A = UUID.fromString("00000000-0000-0000-0000-000000000415");
    private static final UUID TENANT_ADMIN_B = UUID.fromString("00000000-0000-0000-0000-000000000416");
    private static final UUID CUSTOMER_USER_A = UUID.fromString("00000000-0000-0000-0000-000000000417");
    private static final UUID CUSTOMER_USER_B = UUID.fromString("00000000-0000-0000-0000-000000000418");
    private static final UUID SYSTEM_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000419");

    @Autowired MockMvc mockMvc;
    @Autowired TenantMapper tenantMapper;
    @Autowired CustomerMapper customerMapper;
    @Autowired UserMapper userMapper;
    @Autowired
    UserCredentialMapper credentialsMapper;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedIsolatedOrganizationGraph() {
        for (UUID userId : List.of(TENANT_ADMIN_A, TENANT_ADMIN_B, CUSTOMER_USER_A, CUSTOMER_USER_B, SYSTEM_ADMIN)) {
            credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                    .eq(UserCredentialEntity::getUserId, userId));
            userMapper.deleteById(userId);
        }
        customerMapper.deleteById(CUSTOMER_A);
        customerMapper.deleteById(CUSTOMER_B);
        tenantMapper.deleteById(TENANT_A);
        tenantMapper.deleteById(TENANT_B);

        TenantEntity tenantA = tenant(TENANT_A, "Scenario 4 tenant A");
        TenantEntity tenantB = tenant(TENANT_B, "Scenario 4 tenant B");
        tenantMapper.insert(tenantA);
        tenantMapper.insert(tenantB);

        CustomerEntity customerA = customer(CUSTOMER_A, TENANT_A, "Scenario 4 customer A");
        CustomerEntity customerB = customer(CUSTOMER_B, TENANT_B, "Scenario 4 customer B");
        customerMapper.insert(customerA);
        customerMapper.insert(customerB);

        insertUser(TENANT_ADMIN_A, TENANT_A, null, "tenant-admin-a@scenario4.test", UserAuthority.TENANT_ADMIN);
        insertUser(TENANT_ADMIN_B, TENANT_B, null, "tenant-admin-b@scenario4.test", UserAuthority.TENANT_ADMIN);
        insertUser(CUSTOMER_USER_A, TENANT_A, CUSTOMER_A, "customer-user-a@scenario4.test", UserAuthority.CUSTOMER_USER);
        insertUser(CUSTOMER_USER_B, TENANT_B, CUSTOMER_B, "customer-user-b@scenario4.test", UserAuthority.CUSTOMER_USER);
        insertUser(SYSTEM_ADMIN, null, null, "system-admin@scenario4.test", UserAuthority.SYS_ADMIN);
    }

    @Test
    void tenantAndCustomerRolesCannotCrossOrganizationBoundaries() throws Exception {
        String tenantAdminToken = login("tenant-admin-a@scenario4.test");
        String customerUserToken = login("customer-user-a@scenario4.test");

        mockMvc.perform(get("/api/tenants/" + TENANT_A).header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + TENANT_B).header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/" + CUSTOMER_A).header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/customers/" + CUSTOMER_B).header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/customers/" + CUSTOMER_A).header("Authorization", bearer(customerUserToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/customers/" + CUSTOMER_B).header("Authorization", bearer(customerUserToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + TENANT_A).header("Authorization", bearer(customerUserToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedListsAndMixedIdLookupsDoNotLeakOtherTenantData() throws Exception {
        String tenantAdminToken = login("tenant-admin-a@scenario4.test");
        String customerUserToken = login("customer-user-a@scenario4.test");
        String systemAdminToken = login("system-admin@scenario4.test");

        mockMvc.perform(get("/api/tenants").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(TENANT_A.toString()));
        mockMvc.perform(get("/api/customers").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(CUSTOMER_A.toString()));
        mockMvc.perform(get("/api/users").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(customerUserToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(CUSTOMER_USER_A.toString()));
        mockMvc.perform(get("/api/tenants/" + TENANT_A + "/users").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/customers/" + CUSTOMER_A + "/users").param("pageSize", "20").param("page", "0")
                        .header("Authorization", bearer(customerUserToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(CUSTOMER_USER_A.toString()));

        mockMvc.perform(get("/api/tenants").param("tenantIds", TENANT_A.toString(), TENANT_B.toString())
                        .header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[1].id").exists());
        mockMvc.perform(get("/api/customers").param("customerIds", CUSTOMER_A.toString(), CUSTOMER_B.toString())
                        .header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[1].id").exists());
        mockMvc.perform(get("/api/tenants").param("tenantIds", TENANT_A.toString(), TENANT_B.toString())
                        .header("Authorization", bearer(tenantAdminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingTenantOrCustomerRemovesItsUsersFromApi() throws Exception {
        String systemAdminToken = login("system-admin@scenario4.test");

        mockMvc.perform(delete("/api/customers/" + CUSTOMER_A).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/customers/" + CUSTOMER_A).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/" + CUSTOMER_USER_A).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tenants/" + TENANT_A).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/tenants/" + TENANT_B).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + TENANT_B).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customers/" + CUSTOMER_B).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/" + TENANT_ADMIN_B).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/" + CUSTOMER_USER_B).header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void scopedUpdatesCannotMoveCustomerOrPromoteUser() throws Exception {
        String tenantAdminToken = login("tenant-admin-a@scenario4.test");

        mockMvc.perform(post("/api/customers")
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + CUSTOMER_A + "\",\"tenantId\":\"" + TENANT_B
                                + "\",\"title\":\"updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(tenantAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + TENANT_ADMIN_A + "\",\"tenantId\":\"" + TENANT_B
                                + "\",\"authority\":\"SYS_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$.authority").value("TENANT_ADMIN"));

        assertEquals(TENANT_A, customerMapper.selectById(CUSTOMER_A).getTenantId());
        assertEquals(TENANT_A, userMapper.selectById(TENANT_ADMIN_A).getTenantId());
        assertEquals(UserAuthority.TENANT_ADMIN, userMapper.selectById(TENANT_ADMIN_A).getAuthority());
    }

    @Test
    void tenantAdminCannotCreateSystemAdminUser() throws Exception {
        String tenantAdminToken = login("tenant-admin-a@scenario4.test");
        String email = "forbidden-system-admin-" + UUID.randomUUID() + "@scenario4.test";

        try {
            mockMvc.perform(post("/api/users")
                            .header("Authorization", bearer(tenantAdminToken))
                            .param("sendActivationMail", "false")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tenantId\":\"" + TENANT_A
                                    + "\",\"email\":\"" + email
                                    + "\",\"authority\":\"SYS_ADMIN\"}"))
                    .andExpect(status().isForbidden());
        } finally {
            List<UserEntity> created = userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email));
            for (UserEntity user : created) {
                credentialsMapper.delete(new LambdaQueryWrapper<UserCredentialEntity>()
                        .eq(UserCredentialEntity::getUserId, user.getId()));
                userMapper.deleteById(user.getId());
            }
        }
    }

    private TenantEntity tenant(UUID id, String title) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(PROFILE_ID);
        tenant.setTitle(title);
        tenant.setVersion(1L);
        return tenant;
    }

    private CustomerEntity customer(UUID id, UUID tenantId, String title) {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(id);
        customer.setCreatedTime(System.currentTimeMillis());
        customer.setTenantId(tenantId);
        customer.setTitle(title);
        customer.setVersion(1L);
        return customer;
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
        credentialsMapper.insert(credentials);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
