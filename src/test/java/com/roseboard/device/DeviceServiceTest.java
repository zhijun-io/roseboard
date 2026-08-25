package com.roseboard.device;

import com.roseboard.cache.AttributeCacheEvictionEvent;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.cache.TelemetryLatestCacheEvictionEvent;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.CacheSpec;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.customer.CustomerService;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeEntity;
import com.roseboard.device.attribute.DeviceAttributeMapper;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialMapper;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.device.telemetry.TelemetryLatestMapper;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.ota.OtaPackageServiceImpl;
import com.roseboard.tenant.usage.TenantUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {
    @Mock DeviceMapper deviceMapper;
    @Mock
    DeviceCredentialMapper credentialsMapper;
    @Mock DeviceProfileService deviceProfileService;
    @Mock
    DataScopeAuthorizer dataScopeService;
    @Mock CustomerService customerService;
    @Mock OtaPackageServiceImpl otaPackages;
    @Mock TenantUsageService usageService;
    @Mock ApplicationEventPublisher events;
    @Mock CacheTemplate cache;
    @Mock CacheProperties cacheProperties;
    @Mock CacheEvictor cacheEvictions;
    @Mock DeviceAttributeMapper attributeMapper;
    @Mock TelemetryLatestMapper latestMapper;
    @InjectMocks DeviceService service;

    @Test
    void deletingDeviceEvictsAttributeAndTelemetryLatestKeys() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceEntity device = new DeviceEntity();
        device.setTenantId(tenantId);
        device.setId(deviceId);
        device.setVersion(4L);

        DeviceCredentialEntity credentials = new DeviceCredentialEntity();
        credentials.setCredentialsType("ACCESS_TOKEN");
        credentials.setCredentialsId("token");
        DeviceAttributeEntity attribute = new DeviceAttributeEntity();
        attribute.setTenantId(tenantId);
        attribute.setDeviceId(deviceId);
        attribute.setScope(AttributeScope.SERVER);
        attribute.setAttributeKey("firmwareVersion");
        attribute.setVersion(2L);

        when(deviceMapper.selectById(deviceId)).thenReturn(device);
        when(credentialsMapper.selectOne(any())).thenReturn(credentials);
        when(attributeMapper.selectList(any())).thenReturn(List.of(attribute));
        when(latestMapper.keys(tenantId, deviceId)).thenReturn(List.of("temperature"));

        service.deleteById(deviceId);


        verify(credentialsMapper).delete(any());
        verify(deviceMapper).deleteById(deviceId);
        verify(cacheEvictions).publish(new AttributeCacheEvictionEvent(
                tenantId, deviceId, "SERVER", "firmwareVersion", 3L));
        verify(cacheEvictions).publish(new TelemetryLatestCacheEvictionEvent(
                tenantId, deviceId, "temperature", null));
    }
    @Test
    void scopedTenantReadUsesDeviceCache() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceEntity cachedDevice = new DeviceEntity();
        cachedDevice.setTenantId(tenantId);
        cachedDevice.setId(deviceId);
        CacheSpec spec = new CacheSpec("devices", true, java.time.Duration.ofMinutes(5), 10);
        Authentication authentication = mock(Authentication.class);
        when(dataScopeService.current(authentication))
                .thenReturn(SecurityUsers.of(UUID.randomUUID(), tenantId, null));
        when(cacheProperties.spec("devices")).thenReturn(spec);
        when(cache.get(eq(spec), eq(CacheKeyBuilder.device(tenantId, deviceId)), any(), any()))
                .thenReturn(cachedDevice);

        assertThat(service.requireScoped(deviceId, authentication)).isSameAs(cachedDevice);
        verify(deviceMapper, never()).selectById(deviceId);
    }

}
