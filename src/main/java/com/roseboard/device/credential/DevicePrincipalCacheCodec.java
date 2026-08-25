package com.roseboard.device.credential;

import com.roseboard.infrastructure.cache.CacheCodec;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class DevicePrincipalCacheCodec implements CacheCodec<DeviceCredentialService.DevicePrincipal> {
    @Override
    public byte[] encode(DeviceCredentialService.DevicePrincipal value) {
        return String.join("\n", value.deviceId().toString(), value.tenantId().toString(),
                value.credentialId().toString(), value.credentialsType()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public DeviceCredentialService.DevicePrincipal decode(byte[] value) {
        String[] fields = new String(value, StandardCharsets.UTF_8).split("\\n", -1);
        if (fields.length != 4) {
            throw new IllegalArgumentException("Invalid cached device principal");
        }
        return new DeviceCredentialService.DevicePrincipal(
                UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                UUID.fromString(fields[2]), fields[3]);
    }
}
