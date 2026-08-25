package com.roseboard.device.credential;

/** Aligned with ThingsBoard {@code org.thingsboard.server.common.data.security.DeviceCredentialsType}. */
public enum DeviceCredentialType {
    ACCESS_TOKEN,
    X509_CERTIFICATE,
    MQTT_BASIC,
    LWM2M_CREDENTIALS
}
