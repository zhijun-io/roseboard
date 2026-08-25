package com.roseboard.device.attribute;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeviceAttributeStore {
    int MAX_KEYS = 100;

    DeviceAttribute find(UUID tenantId, UUID deviceId, AttributeScope scope, AttributeKey key);

    List<DeviceAttribute> find(UUID tenantId, UUID deviceId, AttributeScope scope,
                               Collection<AttributeKey> keys);

    List<DeviceAttribute> findAll(UUID tenantId, UUID deviceId, AttributeScope scope);

    List<String> keys(UUID tenantId, UUID deviceId, AttributeScope scope);

    DeviceAttribute save(UUID tenantId, UUID deviceId, AttributeScope scope,
                         AttributeKey key, AttributeValue value);

    DeviceAttribute update(UUID tenantId, UUID deviceId, AttributeScope scope,
                           AttributeKey key, AttributeValue value, long expectedVersion);

    void delete(UUID tenantId, UUID deviceId, AttributeScope scope,
                AttributeKey key, long expectedVersion);
}
