package com.roseboard.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class CacheKeyBuilder {
    public static final String PREFIX = "roseboard:cache:v1:";

    private CacheKeyBuilder() {
    }

    public static String defaultDeviceProfile(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return PREFIX + "device-profile-default:" + tenantId;
    }

    public static String device(UUID tenantId, UUID deviceId) {
        return scoped("device", tenantId, deviceId);
    }

    public static String deviceProfile(UUID tenantId, UUID profileId) {
        return scoped("device-profile", tenantId, profileId);
    }

    public static String credentialsAuth(String credentialsType, String lookupKey) {
        Objects.requireNonNull(credentialsType, "credentialsType");
        Objects.requireNonNull(lookupKey, "lookupKey");
        return PREFIX + "credentials-auth:" + credentialsType + ":" + sha256(lookupKey);
    }

    public static String attribute(UUID tenantId, UUID deviceId, String scope, String key) {
        return PREFIX + "attribute:" + tenantId + ":" + deviceId + ":" + scope + ":" + key;
    }

    public static String telemetryLatest(UUID tenantId, UUID deviceId, String key) {
        return PREFIX + "ts-latest:" + tenantId + ":" + deviceId + ":" + key;
    }

    private static String scoped(String kind, UUID tenantId, UUID entityId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entityId, "entityId");
        return PREFIX + kind + ":" + tenantId + ":" + entityId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
