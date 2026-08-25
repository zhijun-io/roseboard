package com.roseboard.device.attribute;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.roseboard.common.JacksonUtils;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.cache.AttributeCacheEvictionEvent;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.infrastructure.cache.JacksonCacheCodec;
import com.roseboard.infrastructure.cache.CacheCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.credential.DeviceCredentialService;

/**
 * Attribute domain service (TB-style): single class implementing the store surface + HTTP auth batch API.
 */
@Service
public class DeviceAttributeService implements DeviceAttributeStore {
    public static final int MAX_BATCH_SIZE = 100;
    private static final CacheCodec<DeviceAttribute> CACHE_CODEC =
            new JacksonCacheCodec<>(DeviceAttribute.class, DeviceAttribute::version);

    private final DeviceAttributeMapper mapper;
    private final DeviceMapper deviceMapper;
    private final AttributeAuthorizationService authorization;
    private final ApplicationEventPublisher events;
    private final CacheTemplate cache;
    private final CacheProperties cacheProperties;
    private final CacheEvictor cacheEvictions;

    public DeviceAttributeService(DeviceAttributeMapper mapper,
                                  DeviceMapper deviceMapper,
                                  AttributeAuthorizationService authorization,
                                  ApplicationEventPublisher events) {
        this(mapper, deviceMapper, authorization, events, null, null, null);
    }

    @Autowired
    public DeviceAttributeService(DeviceAttributeMapper mapper,
                                  DeviceMapper deviceMapper,
                                  AttributeAuthorizationService authorization,
                                  ApplicationEventPublisher events,
                                  CacheTemplate cache,
                                  CacheProperties cacheProperties,
                                  CacheEvictor cacheEvictions) {
        this.mapper = mapper;
        this.deviceMapper = deviceMapper;
        this.authorization = authorization;
        this.events = events;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.cacheEvictions = cacheEvictions;
    }

    public DeviceAttribute saveFromDevice(DeviceCredentialService.DevicePrincipal principal,
                                          AttributeScope scope, AttributeKey key, AttributeValue value) {
        if (scope != AttributeScope.CLIENT) {
            throw new IllegalArgumentException("SERVER attribute write denied for device principal");
        }
        return save(principal.tenantId(), principal.deviceId(), scope, key, value);
    }

    public List<DeviceAttribute> readFromDevice(DeviceCredentialService.DevicePrincipal principal,
                                                AttributeScope scope, Collection<AttributeKey> keys) {
        if (scope == AttributeScope.SERVER) {
            throw new IllegalArgumentException("Device cannot read SERVER attributes");
        }
        if (keys == null || keys.isEmpty()) {
            return findAll(principal.tenantId(), principal.deviceId(), scope);
        }
        return find(principal.tenantId(), principal.deviceId(), scope, keys);
    }
    @Override
    public DeviceAttribute find(UUID tenantId, UUID deviceId, AttributeScope scope, AttributeKey key) {
        requireDeviceInTenant(tenantId, deviceId);
        if (scope == null || key == null) {
            throw new IllegalArgumentException("Attribute scope and key are required");
        }
        String cacheKey = CacheKeyBuilder.attribute(tenantId, deviceId, scope.name(), key.value());
        if (cache == null) {
            DeviceAttributeEntity entity = entity(tenantId, deviceId, scope, key);
            return entity == null ? null : toDomain(entity);
        }
        return cache.get(cacheProperties.spec("attributes"), cacheKey,
                () -> {
                    DeviceAttributeEntity entity = entity(tenantId, deviceId, scope, key);
                    return entity == null ? null : toDomain(entity);
                }, CACHE_CODEC);
    }

    public List<DeviceAttribute> find(UUID tenantId, UUID deviceId, AttributeScope scope,
                                      Collection<AttributeKey> keys) {
        requireDeviceInTenant(tenantId, deviceId);
        if (scope == null) {
            throw new IllegalArgumentException("Attribute scope is required");
        }
        if (keys == null || keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException("Attribute key batch size must be between 1 and " + MAX_KEYS);
        }
        return keys.stream()
                .map(key -> {
                    if (key == null) {
                        throw new IllegalArgumentException("Attribute key batch contains null key");
                    }
                    return find(tenantId, deviceId, scope, key);
                })
                .filter(attribute -> attribute != null)
                .sorted(java.util.Comparator.comparing(attribute -> attribute.key().value()))
                .toList();
    }

    @Override
    public List<DeviceAttribute> findAll(UUID tenantId, UUID deviceId, AttributeScope scope) {
        requireDeviceInTenant(tenantId, deviceId);
        if (scope == null) {
            throw new IllegalArgumentException("Attribute scope is required");
        }
        return mapper.selectList(new LambdaQueryWrapper<DeviceAttributeEntity>()
                        .select(DeviceAttributeEntity::getAttributeKey)
                        .eq(DeviceAttributeEntity::getTenantId, tenantId)
                        .eq(DeviceAttributeEntity::getDeviceId, deviceId)
                        .eq(DeviceAttributeEntity::getScope, scope)
                        .orderByAsc(DeviceAttributeEntity::getAttributeKey))
                .stream()
                .map(DeviceAttributeEntity::getAttributeKey)
                .map(key -> find(tenantId, deviceId, scope, new AttributeKey(key)))
                .toList();
    }


    @Override
    public List<String> keys(UUID tenantId, UUID deviceId, AttributeScope scope) {
        requireDeviceInTenant(tenantId, deviceId);
        if (scope == null) {
            throw new IllegalArgumentException("Attribute scope is required");
        }
        return mapper.selectList(new LambdaQueryWrapper<DeviceAttributeEntity>()
                        .select(DeviceAttributeEntity::getAttributeKey)
                        .eq(DeviceAttributeEntity::getTenantId, tenantId)
                        .eq(DeviceAttributeEntity::getDeviceId, deviceId)
                        .eq(DeviceAttributeEntity::getScope, scope)
                        .orderByAsc(DeviceAttributeEntity::getAttributeKey))
                .stream()
                .map(DeviceAttributeEntity::getAttributeKey)
                .toList();
    }

    @Override
    public DeviceAttribute save(UUID tenantId, UUID deviceId, AttributeScope scope,
                                AttributeKey key, AttributeValue value) {
        requireDeviceInTenant(tenantId, deviceId);
        DeviceAttributeEntity entity = new DeviceAttributeEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setDeviceId(deviceId);
        entity.setScope(scope);
        entity.setAttributeKey(key.value());
        entity.setValue(JacksonUtils.objectMapper().valueToTree(value.value()));
        entity.setVersion(1L);
        entity.setUpdatedTime(System.currentTimeMillis());
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Attribute already exists", duplicate);
        }
        DeviceAttribute saved = toDomain(entity);
        cacheEvictions.publish(new AttributeCacheEvictionEvent(
                tenantId, deviceId, scope.name(), key.value(), saved.version()));
        notifySharedChange(tenantId, deviceId, scope, key, saved.value().value(), null);
        notifyAttributeChanged(tenantId, deviceId, scope, key.value(), saved.value().value(), entity.getUpdatedTime());
        return saved;
    }

    @Override
    public DeviceAttribute update(UUID tenantId, UUID deviceId, AttributeScope scope,
                                  AttributeKey key, AttributeValue value, long expectedVersion) {
        DeviceAttributeEntity current = entity(tenantId, deviceId, scope, key);
        if (current == null) {
            throw new IllegalArgumentException("Attribute not found");
        }
        DeviceAttributeEntity replacement = new DeviceAttributeEntity();
        replacement.setValue(JacksonUtils.objectMapper().valueToTree(value.value()));
        replacement.setVersion(expectedVersion + 1);
        replacement.setUpdatedTime(System.currentTimeMillis());
        int updated = mapper.update(replacement, new LambdaUpdateWrapper<DeviceAttributeEntity>()
                .eq(DeviceAttributeEntity::getId, current.getId())
                .eq(DeviceAttributeEntity::getVersion, expectedVersion));
        if (updated == 0) {
            throw new IllegalStateException("Attribute version conflict");
        }
        DeviceAttribute saved = toDomain(entity(tenantId, deviceId, scope, key));
        cacheEvictions.publish(new AttributeCacheEvictionEvent(
                tenantId, deviceId, scope.name(), key.value(), saved.version()));
        notifySharedChange(tenantId, deviceId, scope, key, saved.value().value(), null);
        notifyAttributeChanged(tenantId, deviceId, scope, key.value(), saved.value().value(), saved.version());
        return saved;
    }

    public long requireVersion(UUID tenantId, UUID deviceId, AttributeScope scope, AttributeKey key) {
        DeviceAttribute current = find(tenantId, deviceId, scope, key);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attribute not found: " + key.value());
        }
        return current.version();
    }

    @Override
    public void delete(UUID tenantId, UUID deviceId, AttributeScope scope,
                       AttributeKey key, long expectedVersion) {
        DeviceAttributeEntity current = entity(tenantId, deviceId, scope, key);
        if (current == null) {
            throw new IllegalArgumentException("Attribute not found");
        }
        int deleted = mapper.delete(new LambdaQueryWrapper<DeviceAttributeEntity>()
                .eq(DeviceAttributeEntity::getId, current.getId())
                .eq(DeviceAttributeEntity::getVersion, expectedVersion));
        if (deleted == 0) {
            throw new IllegalStateException("Attribute version conflict");
        }
        cacheEvictions.publish(new AttributeCacheEvictionEvent(
                tenantId, deviceId, scope.name(), key.value(),
                current.getVersion() == null ? null : current.getVersion() + 1));
        notifySharedChange(tenantId, deviceId, scope, key, null, key.value());
    }

    @Transactional(rollbackFor = Exception.class)
    public AttributeBatchResult writeBatch(Authentication authentication, UUID tenantId, UUID deviceId,
                                           List<AttributeWrite> writes, AttributeBatchMode mode) {
        validateBatch(writes, mode);
        if (mode == AttributeBatchMode.ATOMIC) {
            return writeAtomic(authentication, tenantId, deviceId, writes);
        }
        return writePerItem(authentication, tenantId, deviceId, writes);
    }

    public DeviceAttribute read(Authentication authentication, UUID tenantId, UUID deviceId,
                                AttributeScope scope, AttributeKey key) {
        authorization.requireRead(authentication, scope);
        return find(tenantId, deviceId, scope, key);
    }

    public List<DeviceAttribute> readAll(Authentication authentication, UUID tenantId, UUID deviceId,
                                         AttributeScope scope) {
        authorization.requireRead(authentication, scope);
        return findAll(tenantId, deviceId, scope);
    }

    public List<DeviceAttribute> read(Authentication authentication, UUID tenantId, UUID deviceId,
                                      AttributeScope scope, Collection<AttributeKey> keys) {
        authorization.requireRead(authentication, scope);
        return find(tenantId, deviceId, scope, keys);
    }

    public List<String> keys(Authentication authentication, UUID tenantId, UUID deviceId,
                             AttributeScope scope) {
        authorization.requireRead(authentication, scope);
        return keys(tenantId, deviceId, scope);
    }

    private AttributeBatchResult writeAtomic(Authentication authentication, UUID tenantId, UUID deviceId,
                                             List<AttributeWrite> writes) {
        List<AttributeChange> changes = new ArrayList<>(writes.size());
        for (AttributeWrite write : writes) {
            authorization.requireWrite(authentication, write.scope());
            changes.add(writeOne(tenantId, deviceId, write));
        }
        return new AttributeBatchResult(changes, List.of());
    }

    private AttributeBatchResult writePerItem(Authentication authentication, UUID tenantId, UUID deviceId,
                                              List<AttributeWrite> writes) {
        List<AttributeChange> changes = new ArrayList<>();
        List<AttributeBatchResult.AttributeFailure> failures = new ArrayList<>();
        for (AttributeWrite write : writes) {
            try {
                authorization.requireWrite(authentication, write.scope());
                changes.add(writeOne(tenantId, deviceId, write));
            } catch (RuntimeException failure) {
                failures.add(new AttributeBatchResult.AttributeFailure(
                        write.key(), failure.getMessage()));
            }
        }
        return new AttributeBatchResult(changes, failures);
    }

    private AttributeChange writeOne(UUID tenantId, UUID deviceId, AttributeWrite write) {
        if (write.expectedVersion() == null) {
            DeviceAttribute saved = save(tenantId, deviceId, write.scope(), write.key(), write.value());
            return new AttributeChange(write.key(), saved.version(), true);
        }
        DeviceAttribute updated = update(tenantId, deviceId, write.scope(), write.key(),
                write.value(), write.expectedVersion());
        return new AttributeChange(write.key(), updated.version(), true);
    }

    private static void validateBatch(List<AttributeWrite> writes, AttributeBatchMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Attribute batch mode is required");
        }
        if (writes == null || writes.isEmpty() || writes.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Attribute batch size must be between 1 and "
                    + MAX_BATCH_SIZE);
        }
        for (AttributeWrite write : writes) {
            if (write == null || write.scope() == null || write.key() == null || write.value() == null) {
                throw new IllegalArgumentException("Attribute batch contains invalid item");
            }
        }
    }

    private void requireDeviceInTenant(UUID tenantId, UUID deviceId) {
        if (tenantId == null || deviceId == null) {
            throw new IllegalArgumentException("Attribute scope is required");
        }
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new IllegalArgumentException("Device does not belong to tenant");
        }
    }

    private DeviceAttributeEntity entity(UUID tenantId, UUID deviceId,
                                         AttributeScope scope, AttributeKey key) {
        return mapper.selectOne(new LambdaQueryWrapper<DeviceAttributeEntity>()
                .eq(DeviceAttributeEntity::getTenantId, tenantId)
                .eq(DeviceAttributeEntity::getDeviceId, deviceId)
                .eq(DeviceAttributeEntity::getScope, scope)
                .eq(DeviceAttributeEntity::getAttributeKey, key.value()));
    }

    private void notifySharedChange(UUID tenantId, UUID deviceId, AttributeScope scope, AttributeKey key,
                                    Object value, String deletedKey) {
        if (scope != AttributeScope.SHARED) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>();
        if (value != null) {
            updated.put(key.value(), value);
        }
        List<String> deleted = deletedKey == null ? List.of() : List.of(deletedKey);
        events.publishEvent(new SharedAttributeChangedEvent(tenantId, deviceId, updated, deleted));
    }

    private void notifyAttributeChanged(UUID tenantId, UUID deviceId, AttributeScope scope,
                                        String key, Object value, long updatedTimeMs) {
        if (value == null) {
            return;
        }
        events.publishEvent(new DeviceAttributeChangedEvent(tenantId, deviceId, scope, key, value, updatedTimeMs));
    }

    private static DeviceAttribute toDomain(DeviceAttributeEntity entity) {
        Object value = JacksonUtils.convertValue(entity.getValue(), Object.class);
        return new DeviceAttribute(entity.getTenantId(), entity.getDeviceId(), entity.getScope(),
                new AttributeKey(entity.getAttributeKey()), new AttributeValue(value),
                entity.getVersion(), entity.getUpdatedTime());
    }
}
