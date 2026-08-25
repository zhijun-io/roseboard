package com.roseboard.device.attribute;

import com.roseboard.user.UserAuthority;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceAttributeControllerTest {
    private final DeviceService deviceService = mock(DeviceService.class);
    private final DeviceAttributeService attributeService = mock(DeviceAttributeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DeviceAttributeController(deviceService, attributeService)).build();

    @Test
    void convertsBatchJsonIntoDomainWrites() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setTenantId(tenantId);
        when(deviceService.requireScoped(eq(deviceId), any(), eq(true))).thenReturn(device);
        when(attributeService.writeBatch(any(), eq(tenantId), eq(deviceId), any(), eq(AttributeBatchMode.PER_ITEM)))
                .thenReturn(new AttributeBatchResult(List.of(), List.of()));

        mvc.perform(post("/api/devices/{deviceId}/attributes", deviceId)
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a",
                                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name()))))
                        .contentType("application/json")
                        .content("""
                                {
                                  "mode": "PER_ITEM",
                                  "items": [
                                    {"scope":"SERVER","key":"enabled","value":true}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        verify(attributeService).writeBatch(any(), eq(tenantId), eq(deviceId), any(),
                eq(AttributeBatchMode.PER_ITEM));
    }

    @Test
    void listsKeysAndDeletesAttributes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setTenantId(tenantId);
        when(deviceService.requireScoped(eq(deviceId), any())).thenReturn(device);
        when(attributeService.keys(any(), eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER)))
                .thenReturn(List.of("enabled"));
        when(attributeService.requireVersion(eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER),
                eq(new AttributeKey("enabled"))))
                .thenReturn(3L);

        mvc.perform(get("/api/devices/{deviceId}/attributes/keys", deviceId)
                        .param("scope", "SERVER")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a",
                                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("enabled"));

        mvc.perform(delete("/api/devices/{deviceId}/attributes", deviceId)
                        .param("scope", "SERVER")
                        .param("keys", "enabled")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a",
                                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())))))
                .andExpect(status().isOk());

        verify(attributeService).delete(eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER),
                eq(new AttributeKey("enabled")), eq(3L));
    }

    @Test
    void listsAttributesByScopeAndKeys() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceEntity device = new DeviceEntity();
        device.setId(deviceId);
        device.setTenantId(tenantId);
        DeviceAttribute attr = new DeviceAttribute(tenantId, deviceId, AttributeScope.SERVER,
                new AttributeKey("enabled"), new AttributeValue(true), 1L, 1L);
        when(deviceService.requireScoped(eq(deviceId), any())).thenReturn(device);
        when(attributeService.readAll(any(), eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER)))
                .thenReturn(List.of(attr));
        when(attributeService.read(any(), eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER),
                org.mockito.ArgumentMatchers.<Collection<AttributeKey>>any()))
                .thenReturn(List.of(attr));

        mvc.perform(get("/api/devices/{deviceId}/attributes", deviceId)
                        .param("scope", "SERVER")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a",
                                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key.value").value("enabled"));

        mvc.perform(get("/api/devices/{deviceId}/attributes", deviceId)
                        .param("scope", "SERVER")
                        .param("keys", "enabled")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a",
                                List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key.value").value("enabled"));

        verify(attributeService).readAll(any(), eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER));
        verify(attributeService).read(any(), eq(tenantId), eq(deviceId), eq(AttributeScope.SERVER),
                org.mockito.ArgumentMatchers.<Collection<AttributeKey>>any());
    }
}
