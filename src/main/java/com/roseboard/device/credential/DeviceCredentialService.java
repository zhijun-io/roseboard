package com.roseboard.device.credential;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.cache.DeviceCredentialsCacheEvictionEvent;
import com.roseboard.cache.CredentialAuthCacheKeys;
import com.roseboard.cache.CacheKeyBuilder;
import com.roseboard.cache.MqttCredentialLookups;
import com.roseboard.infrastructure.cache.CacheTemplate;
import com.roseboard.infrastructure.cache.eviction.CacheEvictor;
import com.roseboard.infrastructure.cache.CacheCodec;
import com.roseboard.infrastructure.cache.CacheProperties;
import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ThingsBoard-aligned credentials: one row per device, lookup by credentialsId,
 * type payload stored as plaintext in credentialsValue (TB shape).
 */
@Service
public class DeviceCredentialService {
    private static final DevicePrincipalCacheCodec PRINCIPAL_CODEC = new DevicePrincipalCacheCodec();
    private static final CacheCodec<CredentialAuthValue> CREDENTIAL_AUTH_CODEC =
            new CacheCodec<>() {
                @Override
                public byte[] encode(CredentialAuthValue value) {
                    return String.join("\n",
                            value.principal().deviceId().toString(),
                            value.principal().tenantId().toString(),
                            value.principal().credentialId().toString(),
                            value.principal().credentialsType(),
                            value.verifierHash()).getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public CredentialAuthValue decode(byte[] value) {
                    String[] fields = new String(value, StandardCharsets.UTF_8).split("\\n", -1);
                    if (fields.length != 5) {
                        throw new IllegalArgumentException("Invalid cached credential auth value");
                    }
                    return new CredentialAuthValue(
                            new DevicePrincipal(UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                                    UUID.fromString(fields[2]), fields[3]),
                            fields[4]);
                }
            };

    private final DeviceCredentialMapper mapper;
    private final DeviceMapper deviceMapper;
    private final CacheTemplate cache;
    private final CacheProperties cacheProperties;
    private final CacheEvictor cacheEvictions;
    private final SecureRandom random = new SecureRandom();

    public DeviceCredentialService(DeviceCredentialMapper mapper, DeviceMapper deviceMapper,
                                   CacheTemplate cache, CacheProperties cacheProperties,
                                   CacheEvictor cacheEvictions) {
        this.mapper = mapper;
        this.deviceMapper = deviceMapper;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.cacheEvictions = cacheEvictions;
    }

    public DeviceCredentialEntity findByDeviceId(UUID deviceId) {
        return mapper.selectOne(new LambdaQueryWrapper<DeviceCredentialEntity>()
                .eq(DeviceCredentialEntity::getDeviceId, deviceId));
    }

    public DeviceCredentialEntity findEnabledByCredentialsId(String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<DeviceCredentialEntity>()
                .eq(DeviceCredentialEntity::getCredentialsId, credentialsId)
                .eq(DeviceCredentialEntity::getEnabled, true));
    }

    /** Masked query view — never returns recoverable secret. */
    public DeviceCredentialEntity findMasked(UUID tenantId, UUID deviceId) {
        requireDevice(tenantId, deviceId);
        return copyMasked(findByDeviceId(deviceId));
    }

    /** Masked credentials or 404. */
    public DeviceCredentialEntity requireMasked(UUID tenantId, UUID deviceId) {
        DeviceCredentialEntity credentials = findMasked(tenantId, deviceId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device credentials not found");
        }
        return credentials;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceCredentialEntity generateAccessToken(UUID deviceId) {
        DeviceEntity device = requireDeviceExists(deviceId);
        String token = randomToken();
        DeviceCredentialEntity saved = save(device.getTenantId(), deviceId,
                DeviceCredentialType.ACCESS_TOKEN, token, null);
        DeviceCredentialEntity response = copyMasked(saved);
        response.setCredentialsValue(token);
        return response;
    }

    /**
     * Create or replace the single credentials row for a device (TB updateDeviceCredentials).
     * Returns entity with one-time plaintext in {@code credentialsValue} when applicable.
     */
    @Transactional(rollbackFor = Exception.class)
    public DeviceCredentialEntity save(UUID tenantId, UUID deviceId, DeviceCredentialType type,
                                       String credentialsIdOrToken, String credentialsValueJson) {
        requireDevice(tenantId, deviceId);
        Formatted formatted = format(type, credentialsIdOrToken, credentialsValueJson);
        long now = System.currentTimeMillis();
        DeviceCredentialEntity existing = findByDeviceId(deviceId);
        boolean created = existing == null;
        DeviceCredentialEntity entity = created ? new DeviceCredentialEntity() : existing;
        if (created) {
            entity.setId(UUID.randomUUID());
            entity.setCreatedTime(now);
            entity.setDeviceId(deviceId);
            entity.setVersion(1L);
        }
        entity.setCredentialsType(type.name());
        entity.setCredentialsId(formatted.credentialsId());
        entity.setCredentialsValue(formatted.credentialsValue());
        entity.setEnabled(true);
        entity.setLastUpdatedTime(now);
        if (created) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
            entity = mapper.selectById(entity.getId());
        }
        evictCredentialLookups(existing, entity);
        DeviceCredentialEntity response = copyMasked(entity);
        response.setCredentialsValue(oneTimePlaintext(type, formatted));
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceCredentialEntity rotateAccessToken(UUID tenantId, UUID deviceId) {
        DeviceCredentialEntity current = findByDeviceId(deviceId);
        if (current == null || !DeviceCredentialType.ACCESS_TOKEN.name().equals(current.getCredentialsType())) {
            throw new IllegalArgumentException("ACCESS_TOKEN credentials required for rotate");
        }
        requireDevice(tenantId, deviceId);
        return save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, randomToken(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(UUID deviceId) {
        DeviceCredentialEntity credentials = findByDeviceId(deviceId);
        if (credentials == null) {
            return;
        }
        requireDeviceExists(deviceId);
        credentials.setEnabled(false);
        credentials.setLastUpdatedTime(System.currentTimeMillis());
        mapper.updateById(credentials);
        evictCredentialLookups(credentials, null);
    }


    public DevicePrincipal authenticateAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return cache.get(cacheProperties.spec("deviceCredentials"),
                CacheKeyBuilder.credentialsAuth(DeviceCredentialType.ACCESS_TOKEN.name(), token),
                () -> {
                    DeviceCredentialEntity entity = findEnabledByCredentialsId(token);
                    if (entity == null
                            || !DeviceCredentialType.ACCESS_TOKEN.name().equals(entity.getCredentialsType())) {
                        return null;
                    }
                    return principal(entity);
                }, PRINCIPAL_CODEC);
    }
    public DevicePrincipal authenticateMqttCredentials(String clientId, String username, String password) {
        String lookup = MqttCredentialLookups.credentialIdSafe(clientId, username);
        CredentialAuthValue cached = cachedMqttCredentials(lookup);
        if (cached == null && username != null && !username.isBlank()
                && !username.equals(lookup)) {
            cached = cachedMqttCredentials(username);
        }
        return cached != null && matches(cached.verifierHash(), password)
                ? cached.principal() : null;
    }

    public DevicePrincipal authenticateX509Certificate(String certificateHash) {
        if (certificateHash == null || certificateHash.isBlank()) {
            return null;
        }
        return cache.get(cacheProperties.spec("deviceCredentials"),
                CacheKeyBuilder.credentialsAuth(DeviceCredentialType.X509_CERTIFICATE.name(), certificateHash),
                () -> {
                    DeviceCredentialEntity entity = findEnabledByCredentialsId(certificateHash);
                    if (entity == null
                            || !DeviceCredentialType.X509_CERTIFICATE.name().equals(entity.getCredentialsType())) {
                        return null;
                    }
                    return principal(entity);
                }, PRINCIPAL_CODEC);
    }
    public DevicePrincipal authenticateLwm2m(String identity, String key) {
        CredentialAuthValue cached = cachedLwm2mCredentials(identity);
        return cached != null && matches(cached.verifierHash(), key)
                ? cached.principal() : null;
    }
    /**
     * Resolves the TB-compatible device credential headers used by HTTP adapters.
     * Path access tokens remain the primary credential; headers are only fallbacks.
     */
    public DevicePrincipal authenticateHttpCredentials(String authorization,
                                                       String xAuthorization,
                                                       String deviceAccessToken,
                                                       String credentialsId,
                                                       String credentialsValue) {
        for (String candidate : new String[] {
                deviceAccessToken,
                bearerValue(authorization),
                bearerValue(xAuthorization),
                credentialsId
        }) {
            DevicePrincipal principal = authenticateAccessToken(candidate);
            if (principal != null) {
                return principal;
            }
        }
        if (credentialsId != null && credentialsValue != null) {
            DevicePrincipal principal = authenticateMqttCredentials(credentialsId, credentialsId, credentialsValue);
            if (principal != null) {
                return principal;
            }
            principal = authenticateLwm2m(credentialsId, credentialsValue);
            if (principal != null) {
                return principal;
            }
        }
        return null;
    }

    private static String bearerValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int separator = raw.indexOf(' ');
        return separator > 0 && "Bearer".equalsIgnoreCase(raw.substring(0, separator))
                ? raw.substring(separator + 1).trim()
                : raw;
    }

    public DeviceCredentialEntity findEnabledCredentials(UUID deviceId) {
        DeviceCredentialEntity entity = findByDeviceId(deviceId);
        return entity != null && Boolean.TRUE.equals(entity.getEnabled()) ? entity : null;
    }

    public String credentialsValue(DeviceCredentialEntity entity) {
        return entity == null ? null : entity.getCredentialsValue();
    }

    public DeviceCredentialEntity copyMasked(DeviceCredentialEntity entity) {
        if (entity == null) {
            return null;
        }
        DeviceCredentialEntity copy = new DeviceCredentialEntity();
        copy.setId(entity.getId());
        copy.setCreatedTime(entity.getCreatedTime());
        copy.setDeviceId(entity.getDeviceId());
        copy.setCredentialsType(entity.getCredentialsType());
        copy.setEnabled(entity.getEnabled());
        copy.setLastUpdatedTime(entity.getLastUpdatedTime());
        copy.setVersion(entity.getVersion());
        if (!DeviceCredentialType.ACCESS_TOKEN.name().equals(entity.getCredentialsType())) {
            copy.setCredentialsId(entity.getCredentialsId());
        }
        return copy;
    }

    private Formatted format(DeviceCredentialType type, String idOrToken, String valueJson) {
        return switch (type) {
            case ACCESS_TOKEN -> {
                String token = blankToNull(idOrToken);
                if (token == null) {
                    token = randomToken();
                }
                yield new Formatted(token, null);
            }
            case MQTT_BASIC -> {
                JsonNode node = requireObject(valueJson, "Invalid credentials body for simple mqtt credentials");
                String clientId = text(node, "clientId");
                String userName = firstNonBlank(text(node, "userName"), text(node, "username"));
                if (isBlank(clientId) && isBlank(userName)) {
                    throw new IllegalArgumentException("Both mqtt client id and user name are empty");
                }
                if (!isBlank(clientId) && !isBlank(text(node, "password")) && isBlank(userName)) {
                    throw new IllegalArgumentException("Password cannot be specified along with client id only");
                }
                ObjectNode normalized = JacksonUtils.newObjectNode();
                if (!isBlank(clientId)) {
                    normalized.put("clientId", clientId);
                }
                if (!isBlank(userName)) {
                    normalized.put("userName", userName);
                }
                if (!isBlank(text(node, "password"))) {
                    normalized.put("password", text(node, "password"));
                }
                yield new Formatted(MqttCredentialLookups.credentialId(clientId, userName),
                        JacksonUtils.toString(normalized));
            }
            case X509_CERTIFICATE -> {
                JsonNode node = valueJson == null ? JacksonUtils.newObjectNode() : requireObject(valueJson, "Invalid X509 credentials");
                String fingerprint = firstNonBlank(idOrToken, text(node, "fingerprint"));
                if (isBlank(fingerprint)) {
                    throw new IllegalArgumentException("X509 fingerprint required");
                }
                ObjectNode normalized = JacksonUtils.newObjectNode();
                java.util.Iterator<String> names = node.propertyNames().iterator();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode v = node.get(name);
                    if (v != null && v.isValueNode()) {
                        normalized.put(name, v.asText());
                    }
                }
                if (!normalized.has("fingerprint")) {
                    normalized.put("fingerprint", fingerprint);
                }
                yield new Formatted(fingerprint, JacksonUtils.toString(normalized));
            }
            case LWM2M_CREDENTIALS -> {
                JsonNode node = requireObject(valueJson, "Invalid credentials body for LwM2M credentials");
                if (node.has("blob") || node.has("raw")) {
                    throw new IllegalArgumentException("LwM2M credentials must be structured, not opaque blob");
                }
                String identity = firstNonBlank(idOrToken, text(node, "identity"));
                String securityMode = text(node, "securityMode");
                if (isBlank(identity) || isBlank(securityMode)) {
                    throw new IllegalArgumentException("LwM2M identity and securityMode required");
                }
                yield new Formatted(identity, JacksonUtils.toString(node));
            }
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }


    private JsonNode valueNode(DeviceCredentialEntity entity) {
        String value = credentialsValue(entity);
        return value == null ? null : JacksonUtils.toJsonNode(value);
    }
    private CredentialAuthValue cachedMqttCredentials(String lookup) {
        if (lookup == null || lookup.isBlank()) {
            return null;
        }
        return cache.get(cacheProperties.spec("deviceCredentials"),
                CacheKeyBuilder.credentialsAuth(DeviceCredentialType.MQTT_BASIC.name(), lookup),
                () -> loadCredentialAuth(lookup, DeviceCredentialType.MQTT_BASIC.name(), "password", null),
                CREDENTIAL_AUTH_CODEC);
    }

    private CredentialAuthValue cachedLwm2mCredentials(String lookup) {
        if (lookup == null || lookup.isBlank()) {
            return null;
        }
        return cache.get(cacheProperties.spec("deviceCredentials"),
                CacheKeyBuilder.credentialsAuth(DeviceCredentialType.LWM2M_CREDENTIALS.name(), lookup),
                () -> loadCredentialAuth(lookup, DeviceCredentialType.LWM2M_CREDENTIALS.name(),
                        "key", "PSK"),
                CREDENTIAL_AUTH_CODEC);
    }

    private CredentialAuthValue loadCredentialAuth(String lookup, String type,
                                                    String secretField, String requiredSecurityMode) {
        DeviceCredentialEntity entity = findEnabledByCredentialsId(lookup);
        if (entity == null || !type.equals(entity.getCredentialsType())) {
            return null;
        }
        JsonNode node = valueNode(entity);
        if (node == null || (requiredSecurityMode != null
                && !requiredSecurityMode.equals(text(node, "securityMode")))) {
            return null;
        }
        String secret = text(node, secretField);
        DevicePrincipal principal = principal(entity);
        return secret == null || principal == null
                ? null : new CredentialAuthValue(principal, sha256(secret));
    }

    private static boolean matches(String expectedHash, String secret) {
        if (expectedHash == null || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                sha256(secret).getBytes(StandardCharsets.UTF_8));
    }


    private DevicePrincipal principal(DeviceCredentialEntity entity) {
        DeviceEntity device = deviceMapper.selectById(entity.getDeviceId());
        if (device == null) {
            return null;
        }
        return new DevicePrincipal(
                entity.getDeviceId(),
                device.getTenantId(),
                entity.getId(),
                entity.getCredentialsType());
    }

    private DeviceEntity requireDevice(UUID tenantId, UUID deviceId) {
        DeviceEntity device = requireDeviceExists(deviceId);
        if (!tenantId.equals(device.getTenantId())) {
            throw new IllegalArgumentException("device tenant scope denied");
        }
        return device;
    }

    private DeviceEntity requireDeviceExists(UUID deviceId) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("device not found");
        }
        return device;
    }

    private String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(32));
    }

    private static String oneTimePlaintext(DeviceCredentialType type, Formatted formatted) {
        return switch (type) {
            case ACCESS_TOKEN -> formatted.credentialsId();
            case MQTT_BASIC -> text(JacksonUtils.toJsonNode(formatted.credentialsValue()), "password");
            case LWM2M_CREDENTIALS -> text(JacksonUtils.toJsonNode(formatted.credentialsValue()), "key");
            case X509_CERTIFICATE -> null;
        };
    }

    private static JsonNode requireObject(String json, String message) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        JsonNode node = JacksonUtils.toJsonNode(json);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(message);
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private void evictCredentialLookups(DeviceCredentialEntity previous, DeviceCredentialEntity current) {
        List<String> keys = new ArrayList<>();
        if (previous != null) {
            keys.addAll(credentialKeys(previous));
        }
        if (current != null) {
            for (String key : credentialKeys(current)) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        cacheEvictions.publish(new DeviceCredentialsCacheEvictionEvent(keys, null));
    }

    private static List<String> credentialKeys(DeviceCredentialEntity entity) {
        return CredentialAuthCacheKeys.from(entity.getCredentialsType(),
                entity.getCredentialsId(), entity.getCredentialsValue());
    }

    private record CredentialAuthValue(DevicePrincipal principal, String verifierHash) {
    }

    private record Formatted(String credentialsId, String credentialsValue) {
    }

    public record DevicePrincipal(
            UUID deviceId,
            UUID tenantId,
            UUID credentialId,
            String credentialsType) {
    }
}
