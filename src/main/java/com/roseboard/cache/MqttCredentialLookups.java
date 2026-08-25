package com.roseboard.cache;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** ThingsBoard-compatible MQTT credential lookup identifiers used as cache and credentialsId material. */
public final class MqttCredentialLookups {

    private MqttCredentialLookups() {
    }

    public static String credentialId(String clientId, String username) {
        boolean hasClient = StringUtils.isNoneBlank(clientId);
        boolean hasUser = StringUtils.isNoneBlank(username);
        if (!hasClient) {
            return username;
        }
        if (!hasUser) {
            return sha3(clientId);
        }
        return sha3(clientId + "|" + username);
    }

    public static String credentialIdSafe(String clientId, String username) {
        try {
            return credentialId(clientId, username);
        } catch (RuntimeException ex) {
            return username;
        }
    }

    private static String sha3(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA3-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 unavailable", e);
        }
    }
}
