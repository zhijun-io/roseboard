package com.roseboard.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.cache.CredentialAuthCacheKeys;
import com.roseboard.cache.DeviceCacheEvictionEvent;
import com.roseboard.cache.AttributeCacheEvictionEvent;
import com.roseboard.cache.TelemetryLatestCacheEvictionEvent;
import com.roseboard.cache.DeviceCredentialsCacheEvictionEvent;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.JacksonCacheCodec;
import com.roseboard.infrastructure.cache.CacheCodec;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.device.attribute.AttributeAuthorizationService;
import com.roseboard.customer.CustomerService;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.attribute.DeviceAttributeEntity;
import com.roseboard.device.attribute.DeviceAttributeMapper;
import com.roseboard.device.credential.DeviceCredentialMapper;
import com.roseboard.device.telemetry.TelemetryLatestMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.ota.OtaPackageServiceImpl;
import com.roseboard.ota.OtaPackageType;
import com.roseboard.tenant.usage.TenantUsageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class DeviceService {
    private static final CacheCodec<DeviceEntity> CACHE_CODEC =
            new JacksonCacheCodec<>(DeviceEntity.class, DeviceEntity::getVersion);

    private final DeviceMapper deviceMapper;
    private final DeviceCredentialMapper credentialsMapper;
    private final DeviceAttributeMapper attributeMapper;
    private final TelemetryLatestMapper latestMapper;
    private final DeviceProfileService deviceProfileService;
    private final DataScopeAuthorizer dataScopeService;
    private final CustomerService customerService;
    private final OtaPackageServiceImpl otaPackages;
    private final TenantUsageService usageService;
    private final ApplicationEventPublisher events;
    private final CacheTemplate cache;
    private final CacheProperties cacheProperties;
    private final CacheEvictor cacheEvictions;

    public DeviceService(DeviceMapper deviceMapper, DeviceCredentialMapper credentialsMapper,
                         DeviceAttributeMapper attributeMapper, TelemetryLatestMapper latestMapper,
                         DeviceProfileService deviceProfileService, DataScopeAuthorizer dataScopeService,
                         CustomerService customerService, OtaPackageServiceImpl otaPackages,
                         TenantUsageService usageService, ApplicationEventPublisher events,
                         CacheTemplate cache, CacheProperties cacheProperties,
                         CacheEvictor cacheEvictions) {
        this.deviceMapper = deviceMapper;
        this.credentialsMapper = credentialsMapper;
        this.attributeMapper = attributeMapper;
        this.latestMapper = latestMapper;
        this.deviceProfileService = deviceProfileService;
        this.dataScopeService = dataScopeService;
        this.customerService = customerService;
        this.otaPackages = otaPackages;
        this.usageService = usageService;
        this.events = events;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.cacheEvictions = cacheEvictions;
    }

    public DeviceEntity findById(UUID deviceId) {
        return deviceMapper.selectById(deviceId);
    }

    public DeviceEntity findByTenantAndId(UUID tenantId, UUID deviceId) {
        String key = CacheKeyBuilder.device(tenantId, deviceId);
        return cache.get(cacheProperties.spec("devices"), key,
                () -> deviceMapper.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                        .eq(DeviceEntity::getTenantId, tenantId)
                        .eq(DeviceEntity::getId, deviceId)),
                CACHE_CODEC);
    }

    public DeviceEntity requireById(UUID deviceId) {
        DeviceEntity device = findById(deviceId);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        return device;
    }

    public DeviceEntity requireScoped(UUID deviceId, Authentication authentication) {
        return requireScoped(deviceId, authentication, false);
    }

    public DeviceEntity requireScoped(UUID deviceId, Authentication authentication,
                                      boolean allowDevicePrincipal) {
        SecurityUsers scope = dataScopeService.current(authentication);
        DeviceEntity device = scope.isSystemAdmin() || scope.getTenantId() == null
                ? findById(deviceId)
                : findByTenantAndId(scope.getTenantId(), deviceId);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        requireScope(authentication, device, allowDevicePrincipal);
        return device;
    }

    public void requireScope(Authentication authentication, DeviceEntity device) {
        requireScope(authentication, device, false);
    }

    public boolean allowed(Authentication authentication, DeviceEntity device) {
        try {
            requireScope(authentication, device);
            return true;
        } catch (ResponseStatusException exception) {
            return false;
        }
    }

    private void requireScope(Authentication authentication, DeviceEntity device,
                              boolean allowDevicePrincipal) {
        if (allowDevicePrincipal && authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> AttributeAuthorizationService.DEVICE_AUTHENTICATED
                        .equals(authority.getAuthority()))) {
            return;
        }
        SecurityUsers scope = dataScopeService.current(authentication);
        if (scope.isSystemAdmin()) {
            return;
        }
        if (scope.getTenantId() == null || !scope.getTenantId().equals(device.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device tenant scope denied");
        }
        if (scope.getCustomerId() != null && !scope.getCustomerId().equals(device.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device customer scope denied");
        }
    }

    public List<DeviceEntity> findByIds(Collection<UUID> deviceIds) {
        return deviceMapper.selectBatchIds(deviceIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity saveWithScope(DeviceEntity device, Authentication authentication) {
        if (device.getId() == null) {
            requireTenantAndCustomerScope(authentication, device.getTenantId(), device.getCustomerId());
        } else {
            DeviceEntity existing = requireScoped(device.getId(), authentication);
            if (device.getTenantId() != null && !device.getTenantId().equals(existing.getTenantId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device tenant cannot change");
            }
            if (device.getCustomerId() != null && !device.getCustomerId().equals(existing.getCustomerId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device customer cannot change");
            }
            device.setTenantId(existing.getTenantId());
            device.setCustomerId(existing.getCustomerId());
        }
        if (!StringUtils.hasText(device.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device name is required");
        }
        otaPackages.requireAssignable(device.getTenantId(), device.getFirmwareId(), OtaPackageType.FIRMWARE);
        otaPackages.requireAssignable(device.getTenantId(), device.getSoftwareId(), OtaPackageType.SOFTWARE);
        return save(device);
    }

    public void requireTenantAndCustomerScope(Authentication authentication, UUID tenantId, UUID customerId) {
        dataScopeService.requireTenant(authentication, tenantId);
        if (customerId == null) {
            return;
        }
        dataScopeService.requireCustomer(authentication, customerId);
        customerService.requireBelongsToTenant(customerId, tenantId);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity assignToCustomerScoped(UUID deviceId, UUID customerId, Authentication authentication) {
        DeviceEntity device = requireScoped(deviceId, authentication);
        dataScopeService.requireCustomer(authentication, customerId);
        customerService.requireBelongsToTenant(customerId, device.getTenantId());
        return assignToCustomer(deviceId, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity save(DeviceEntity device) {
        long now = System.currentTimeMillis();
        if (device.getId() == null) {
            usageService.requireEntityQuota(device.getTenantId(), TenantUsageService.MAX_DEVICES);
            device.setId(UUID.randomUUID());
            device.setCreatedTime(now);
            device.setVersion(1L);
            if (device.getDeviceProfileId() == null) {
                DeviceProfileEntity profile = deviceProfileService.findDefaultByTenant(device.getTenantId());
                if (profile == null) {
                    profile = deviceProfileService.ensureDefaultForTenant(device.getTenantId());
                }
                device.setDeviceProfileId(profile.getId());
            } else {
                DeviceProfileEntity profile = deviceProfileService.findById(device.getDeviceProfileId());
                if (profile == null || !device.getTenantId().equals(profile.getTenantId())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Device profile tenant mismatch");
                }
            }
            deviceMapper.insert(device);
            DeviceEntity created = deviceMapper.selectById(device.getId());
            cacheEvictions.publish(new DeviceCacheEvictionEvent(
                    created.getTenantId(), created.getId(), created.getVersion()));
            events.publishEvent(new DeviceLifecycleEvent(
                    created.getTenantId(),
                    created.getCustomerId(),
                    created.getType(),
                    created.getName(),
                    DeviceLifecycleEvent.Action.CREATED));
            return created;
        }
        DeviceEntity existing = deviceMapper.selectById(device.getId());
        if (existing != null) {
            if (device.getDeviceProfileId() == null) {
                device.setDeviceProfileId(existing.getDeviceProfileId());
            } else {
                DeviceProfileEntity profile = deviceProfileService.findById(device.getDeviceProfileId());
                if (profile == null || !existing.getTenantId().equals(profile.getTenantId())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Device profile tenant mismatch");
                }
            }
        }
        deviceMapper.updateById(device);
        DeviceEntity saved = deviceMapper.selectById(device.getId());
        cacheEvictions.publish(new DeviceCacheEvictionEvent(
                saved.getTenantId(), saved.getId(), saved.getVersion()));
        return saved;
    }
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(UUID deviceId) {
        DeviceEntity existing = deviceMapper.selectById(deviceId);
        List<DeviceAttributeEntity> attributes = existing == null ? List.of()
                : attributeMapper.selectList(new LambdaQueryWrapper<DeviceAttributeEntity>()
                        .eq(DeviceAttributeEntity::getTenantId, existing.getTenantId())
                        .eq(DeviceAttributeEntity::getDeviceId, deviceId));
        List<String> telemetryKeys = existing == null ? List.of()
                : latestMapper.keys(existing.getTenantId(), deviceId);
        DeviceCredentialEntity credentials = credentialsMapper.selectOne(
                new LambdaQueryWrapper<DeviceCredentialEntity>()
                        .eq(DeviceCredentialEntity::getDeviceId, deviceId));
        credentialsMapper.delete(new LambdaQueryWrapper<DeviceCredentialEntity>()
                .eq(DeviceCredentialEntity::getDeviceId, deviceId));
        deviceMapper.deleteById(deviceId);
        if (existing != null) {
            cacheEvictions.publish(new DeviceCacheEvictionEvent(
                    existing.getTenantId(), existing.getId(),
                    existing.getVersion() == null ? null : existing.getVersion() + 1));
            if (credentials != null) {
                cacheEvictions.publish(new DeviceCredentialsCacheEvictionEvent(
                        CredentialAuthCacheKeys.from(credentials.getCredentialsType(),
                                credentials.getCredentialsId(), credentials.getCredentialsValue()),
                        null));
            }
            for (DeviceAttributeEntity attribute : attributes) {
                cacheEvictions.publish(new AttributeCacheEvictionEvent(
                        existing.getTenantId(), deviceId, attribute.getScope().name(),
                        attribute.getAttributeKey(),
                        attribute.getVersion() == null ? null : attribute.getVersion() + 1));
            }
            for (String key : telemetryKeys) {
                cacheEvictions.publish(new TelemetryLatestCacheEvictionEvent(
                        existing.getTenantId(), deviceId, key, null));
            }
            events.publishEvent(new DeviceLifecycleEvent(
                    existing.getTenantId(),
                    existing.getCustomerId(),
                    existing.getType(),
                    existing.getName(),
                    DeviceLifecycleEvent.Action.DELETED));
        }
    }

    public PageData<DeviceEntity> findPage(long pageSize, long page, String textSearch) {
        return findPage(pageSize, page, textSearch, null, null);
    }

    public PageData<DeviceEntity> findPage(long pageSize, long page, String textSearch,
                                           String type, UUID deviceProfileId) {
        return toPageData(deviceMapper.selectPage(pageSize, page, textSearch, type, deviceProfileId),
                pageSize, page);
    }

    public PageData<DeviceEntity> findPageByTenant(UUID tenantId, long pageSize, long page, String textSearch) {
        return findPageByTenant(tenantId, pageSize, page, textSearch, null, null);
    }

    public PageData<DeviceEntity> findPageByTenant(UUID tenantId, long pageSize, long page, String textSearch,
                                                   String type, UUID deviceProfileId) {
        return toPageData(deviceMapper.selectPageByTenant(tenantId, pageSize, page, textSearch,
                type, deviceProfileId), pageSize, page);
    }

    public PageData<DeviceEntity> findPageByCustomer(UUID customerId, long pageSize, long page, String textSearch) {
        return findPageByCustomer(customerId, pageSize, page, textSearch, null, null);
    }

    public PageData<DeviceEntity> findPageByCustomer(UUID customerId, long pageSize, long page, String textSearch,
                                                     String type, UUID deviceProfileId) {
        return toPageData(deviceMapper.selectPageByCustomer(customerId, pageSize, page, textSearch,
                type, deviceProfileId), pageSize, page);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity assignToCustomer(UUID deviceId, UUID customerId) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Device not found");
        }
        deviceMapper.update(null, new LambdaUpdateWrapper<DeviceEntity>()
                .eq(DeviceEntity::getId, deviceId)
                .set(DeviceEntity::getCustomerId, customerId));
        DeviceEntity saved = deviceMapper.selectById(deviceId);
        cacheEvictions.publish(new DeviceCacheEvictionEvent(
                saved.getTenantId(), saved.getId(), saved.getVersion()));
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity unassignFromCustomer(UUID deviceId) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Device not found");
        }
        if (device.getCustomerId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Device isn't assigned to any customer!");
        }
        deviceMapper.update(null, new LambdaUpdateWrapper<DeviceEntity>()
                .eq(DeviceEntity::getId, deviceId)
                .set(DeviceEntity::getCustomerId, null));
        DeviceEntity saved = deviceMapper.selectById(deviceId);
        cacheEvictions.publish(new DeviceCacheEvictionEvent(
                saved.getTenantId(), saved.getId(), saved.getVersion()));
        return saved;
    }

    private PageData<DeviceEntity> toPageData(Page<DeviceEntity> result, long pageSize, long page) {
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }
}
