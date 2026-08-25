package com.roseboard.device.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.common.PageData;
import com.roseboard.cache.DeviceProfileCacheEvictionEvent;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.JacksonCacheCodec;
import com.roseboard.infrastructure.cache.CacheCodec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;

@Service
public class DeviceProfileService {
    private static final CacheCodec<DeviceProfileEntity> CACHE_CODEC =
            new JacksonCacheCodec<>(DeviceProfileEntity.class, DeviceProfileEntity::getVersion);

    private final DeviceProfileMapper mapper;
    private final DeviceMapper deviceMapper;
    private final CacheTemplate cache;
    private final CacheProperties cacheProperties;
    private final CacheEvictor cacheEvictions;

    public DeviceProfileService(DeviceProfileMapper mapper, DeviceMapper deviceMapper,
                                CacheTemplate cache, CacheProperties cacheProperties,
                                CacheEvictor cacheEvictions) {
        this.mapper = mapper;
        this.deviceMapper = deviceMapper;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.cacheEvictions = cacheEvictions;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceProfileEntity ensureDefaultForTenant(UUID tenantId) {
        DeviceProfileEntity existing = findDefaultByTenant(tenantId);
        if (existing != null) {
            return existing;
        }
        DeviceProfileEntity profile = new DeviceProfileEntity();
        profile.setId(UUID.randomUUID());
        profile.setCreatedTime(System.currentTimeMillis());
        profile.setTenantId(tenantId);
        profile.setName("default");
        profile.setDescription("Default device profile");
        profile.setIsDefault(true);
        profile.setVersion(1L);
        try {
            mapper.insert(profile);
        } catch (RuntimeException duplicate) {
            existing = findDefaultByTenant(tenantId);
            if (existing != null) {
                return existing;
            }
            throw duplicate;
        }
        cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(tenantId, profile.getVersion(),
                List.of(CacheKeyBuilder.deviceProfile(tenantId, profile.getId()),
                        CacheKeyBuilder.defaultDeviceProfile(tenantId))));
        return profile;
    }

    public DeviceProfileEntity findById(UUID id) {
        return mapper.selectById(id);
    }
    public DeviceProfileEntity findByTenantAndId(UUID tenantId, UUID profileId) {
        String key = CacheKeyBuilder.deviceProfile(tenantId, profileId);
        return cache.get(cacheProperties.spec("deviceProfiles"), key,
                () -> mapper.selectOne(new LambdaQueryWrapper<DeviceProfileEntity>()
                        .eq(DeviceProfileEntity::getTenantId, tenantId)
                        .eq(DeviceProfileEntity::getId, profileId)),
                CACHE_CODEC);
    }

    public DeviceProfileEntity requireForTenant(UUID tenantId, UUID profileId) {
        DeviceProfileEntity profile = findByTenantAndId(tenantId, profileId);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device profile not found");
        }
        return profile;
    }

    public DeviceProfileEntity findDefaultByTenant(UUID tenantId) {
        String key = CacheKeyBuilder.defaultDeviceProfile(tenantId);
        return cache.get(cacheProperties.spec("deviceProfiles"), key,
                () -> mapper.selectOne(new LambdaQueryWrapper<DeviceProfileEntity>()
                        .eq(DeviceProfileEntity::getTenantId, tenantId)
                        .eq(DeviceProfileEntity::getIsDefault, true)
                        .last("limit 1")),
                CACHE_CODEC);
    }


    @Transactional(rollbackFor = Exception.class)
    public DeviceProfileEntity save(DeviceProfileEntity profile, long expectedVersion) {
        if (profile.getId() == null) {
            profile.setId(UUID.randomUUID());
            profile.setCreatedTime(System.currentTimeMillis());
            profile.setVersion(1L);
            mapper.insert(profile);
            cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(profile.getTenantId(),
                    profile.getVersion(),
                    List.of(CacheKeyBuilder.deviceProfile(profile.getTenantId(), profile.getId()),
                            CacheKeyBuilder.defaultDeviceProfile(profile.getTenantId()))));
            return profile;
        }
        if (profile.getVersion() == null || profile.getVersion() != expectedVersion) {
            throw conflict("expectedVersion does not match");
        }
        profile.setVersion(expectedVersion);
        if (mapper.updateById(profile) == 0) {
            throw conflict("profile version conflict");
        }
        DeviceProfileEntity saved = mapper.selectById(profile.getId());
        cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(saved.getTenantId(), saved.getVersion(),
                List.of(CacheKeyBuilder.deviceProfile(saved.getTenantId(), saved.getId()),
                        CacheKeyBuilder.defaultDeviceProfile(saved.getTenantId()))));
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceProfileEntity saveForTenant(UUID tenantId, DeviceProfileEntity profile, long expectedVersion) {
        requireTenant(profile, tenantId);
        return save(profile, expectedVersion);
    }

    @Transactional(rollbackFor = Exception.class)
    public void switchDefault(UUID tenantId, UUID profileId) {
        DeviceProfileEntity profile = require(profileId);
        requireTenant(profile, tenantId);
        DeviceProfileEntity previous = findDefaultByTenant(tenantId);
        if (previous != null && !previous.getId().equals(profileId)) {
            previous.setIsDefault(false);
            mapper.updateById(previous);
            DeviceProfileEntity updatedPrevious = mapper.selectById(previous.getId());
            cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(tenantId, updatedPrevious.getVersion(),
                    List.of(CacheKeyBuilder.deviceProfile(tenantId, updatedPrevious.getId()),
                            CacheKeyBuilder.defaultDeviceProfile(tenantId))));
        }
        profile.setIsDefault(true);
        mapper.updateById(profile);
        DeviceProfileEntity saved = mapper.selectById(profileId);
        cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(tenantId, saved.getVersion(),
                List.of(CacheKeyBuilder.deviceProfile(tenantId, saved.getId()),
                        CacheKeyBuilder.defaultDeviceProfile(tenantId))));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID tenantId, UUID profileId) {
        DeviceProfileEntity profile = require(profileId);
        requireTenant(profile, tenantId);
        if (Boolean.TRUE.equals(profile.getIsDefault())
                || deviceMapper.selectCount(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getDeviceProfileId, profileId)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device profile is still in use");
        }
        mapper.deleteById(profileId);
        cacheEvictions.publish(new DeviceProfileCacheEvictionEvent(tenantId,
                profile.getVersion() == null ? null : profile.getVersion() + 1,
                List.of(CacheKeyBuilder.deviceProfile(tenantId, profileId),
                        CacheKeyBuilder.defaultDeviceProfile(tenantId))));
    }

    public PageData<DeviceProfileEntity> findPage(UUID tenantId, long pageSize, long page) {
        var result = mapper.selectPageByTenant(tenantId, pageSize, page);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    private DeviceProfileEntity require(UUID id) {
        DeviceProfileEntity profile = findById(id);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device profile not found");
        }
        return profile;
    }

    private static void requireTenant(DeviceProfileEntity profile, UUID tenantId) {
        if (!tenantId.equals(profile.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device profile tenant scope denied");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
