package com.roseboard.device.rpc;

import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.DeviceService;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;

@SpringBootTest
@Testcontainers
class DeviceRpcServiceIT {
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

    @Autowired TenantMapper tenantMapper;
    @Autowired TenantProfileMapper tenantProfileMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired DeviceProfileService profileService;
    @Autowired DeviceService deviceService;
    @Autowired DeviceRpcService rpcService;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID deviceId;

    @BeforeEach
    void seed() {
        UUID tenantProfileId = UUID.randomUUID();
        TenantProfileEntity tenantProfile = new TenantProfileEntity();
        tenantProfile.setId(tenantProfileId);
        tenantProfile.setCreatedTime(System.currentTimeMillis());
        tenantProfile.setName("rpc-tp-" + tenantProfileId);
        tenantProfile.setIsDefault(false);
        tenantProfile.setIsolatedTbCore(false);
        tenantProfile.setIsolatedTbRuleEngine(false);
        tenantProfileMapper.insert(tenantProfile);

        tenantId = UUID.randomUUID();
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCreatedTime(System.currentTimeMillis());
        tenant.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(tenant);

        otherTenantId = UUID.randomUUID();
        TenantEntity other = new TenantEntity();
        other.setId(otherTenantId);
        other.setCreatedTime(System.currentTimeMillis());
        other.setTenantProfileId(tenantProfileId);
        tenantMapper.insert(other);

        DeviceProfileEntity profile = profileService.ensureDefaultForTenant(tenantId);
        DeviceEntity device = new DeviceEntity();
        device.setId(UUID.randomUUID());
        device.setCreatedTime(System.currentTimeMillis());
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profile.getId());
        device.setName("rpc-device-" + device.getId());
        device.setType("default");
        ObjectNode deviceData = JacksonUtils.newObjectNode();
        deviceData.put("active", true);
        device.setDeviceData(deviceData);
        device.setExternalId(UUID.randomUUID());
        device.setVersion(1L);
        deviceMapper.insert(device);
        deviceId = device.getId();
    }

    @Test
    void deviceDataAndExternalIdRoundTrip() {
        DeviceEntity loaded = deviceService.findById(deviceId);
        assertNotNull(loaded.getDeviceData());
        assertEquals(true, loaded.getDeviceData().path("active").asBoolean());
        assertNotNull(loaded.getExternalId());
    }

    @Test
    void rpcCreateUpdateFindDelete_tbShape() {
        ObjectNode request = JacksonUtils.newObjectNode();
        request.put("method", "setGpio");
        ObjectNode params = request.putObject("params");
        params.put("pin", 7);
        params.put("value", 1);
        request.put("persistent", true);
        request.put("timeout", 5000);
        long expiration = System.currentTimeMillis() + 30_000L;

        DeviceRpcEntity created = rpcService.create(tenantId, deviceId, request, expiration, null);
        assertEquals(RpcStatus.QUEUED.name(), created.getStatus());
        assertEquals("setGpio", created.getRequest().path("method").asText());

        ObjectNode response = JacksonUtils.newObjectNode();
        response.put("ok", true);
        DeviceRpcEntity updated = rpcService.updateStatus(tenantId, created.getId(), RpcStatus.SUCCESSFUL,
                response, null);
        assertEquals(RpcStatus.SUCCESSFUL.name(), updated.getStatus());

        PageData<DeviceRpcEntity> page = rpcService.findPageByDevice(tenantId, deviceId, RpcStatus.SUCCESSFUL, 10, 0);
        assertEquals(1, page.data().size());
        assertEquals(1, page.totalElements());

        assertThrows(ResponseStatusException.class,
                () -> rpcService.findById(otherTenantId, created.getId()));
        assertThrows(ResponseStatusException.class,
                () -> rpcService.create(otherTenantId, deviceId, request, expiration, null));

        rpcService.delete(tenantId, created.getId());
        assertThrows(ResponseStatusException.class, () -> rpcService.findById(tenantId, created.getId()));

        assertThrows(ResponseStatusException.class,
                () -> rpcService.findPageByDevice(tenantId, deviceId, RpcStatus.DELETED, 10, 0));
        assertTrue(rpcService.findPageByDevice(tenantId, deviceId, null, 10, 0).data().isEmpty());
    }

    @Test
    void sentRpcLookupByTransportRequestId() {
        ObjectNode request = JacksonUtils.newObjectNode();
        request.put("method", "reboot");
        request.putObject("params").put("delay", 1);
        DeviceRpcEntity created = rpcService.create(
                tenantId, deviceId, request, System.currentTimeMillis() + 30_000L, null);

        ObjectNode callerInfo = JacksonUtils.newObjectNode();
        callerInfo.put("source", "test");
        rpcService.create(tenantId, deviceId, request, System.currentTimeMillis() + 30_000L, callerInfo);

        DeviceRpcEntity sent = rpcService.claimOldestQueued(tenantId, deviceId, 7).orElseThrow();
        assertEquals(RpcStatus.SENT.name(), sent.getStatus());
        assertEquals(7, sent.getAdditionalInfo().path("transportRequestId").asInt());

        ObjectNode response = JacksonUtils.newObjectNode();
        response.put("done", true);
        rpcService.completeSentReply(tenantId, deviceId, 7, response);

        DeviceRpcEntity completed = rpcService.findById(tenantId, sent.getId());
        assertEquals(RpcStatus.SUCCESSFUL.name(), completed.getStatus());
        assertTrue(completed.getResponse().path("done").asBoolean());
    }
}
