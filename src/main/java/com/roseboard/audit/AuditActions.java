package com.roseboard.audit;

/**
 * 审计动作公共常量。基础设施审计实现只接收开放字符串，不依赖本类。
 */
public final class AuditActions {
    private AuditActions() {
    }

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String MFA_VERIFIED = "MFA_VERIFIED";
    public static final String MFA_FAILED = "MFA_FAILED";
    public static final String MFA_CONFIGURED = "MFA_CONFIGURED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String CUSTOMER_CREATED = "CUSTOMER_CREATED";
    public static final String CUSTOMER_UPDATED = "CUSTOMER_UPDATED";
    public static final String CUSTOMER_DELETED = "CUSTOMER_DELETED";
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_UPDATED = "TENANT_UPDATED";
    public static final String TENANT_DELETED = "TENANT_DELETED";
    public static final String DEVICE_CREATED = "DEVICE_CREATED";
    public static final String DEVICE_UPDATED = "DEVICE_UPDATED";
    public static final String DEVICE_DELETED = "DEVICE_DELETED";
    public static final String DEVICE_CREDENTIALS_GENERATED = "DEVICE_CREDENTIALS_GENERATED";
    public static final String DEVICE_CREDENTIALS_REVOKED = "DEVICE_CREDENTIALS_REVOKED";
    public static final String API_KEY_CREATED = "API_KEY_CREATED";
    public static final String API_KEY_DELETED = "API_KEY_DELETED";
    public static final String API_KEY_UPDATED = "API_KEY_UPDATED";
    public static final String ADMIN_SETTINGS_UPDATED = "ADMIN_SETTINGS_UPDATED";
    public static final String DOMAIN_CREATED = "DOMAIN_CREATED";
    public static final String DOMAIN_UPDATED = "DOMAIN_UPDATED";
    public static final String DOMAIN_DELETED = "DOMAIN_DELETED";
    public static final String OAUTH2_CLIENT_CREATED = "OAUTH2_CLIENT_CREATED";
    public static final String OAUTH2_CLIENT_UPDATED = "OAUTH2_CLIENT_UPDATED";
    public static final String OAUTH2_CLIENT_DELETED = "OAUTH2_CLIENT_DELETED";
    public static final String OAUTH2_TEMPLATE_CREATED = "OAUTH2_TEMPLATE_CREATED";
    public static final String OAUTH2_TEMPLATE_UPDATED = "OAUTH2_TEMPLATE_UPDATED";
    public static final String OAUTH2_TEMPLATE_DELETED = "OAUTH2_TEMPLATE_DELETED";
    public static final String DEVICE_PROFILE_CREATED = "DEVICE_PROFILE_CREATED";
    public static final String DEVICE_PROFILE_UPDATED = "DEVICE_PROFILE_UPDATED";
    public static final String DEVICE_PROFILE_DELETED = "DEVICE_PROFILE_DELETED";
    public static final String DEFAULT_PROFILE_SWITCHED = "DEFAULT_PROFILE_SWITCHED";
    public static final String OTA_PACKAGE_CREATED = "OTA_PACKAGE_CREATED";
    public static final String OTA_PACKAGE_PUBLISHED = "OTA_PACKAGE_PUBLISHED";
    public static final String OTA_PACKAGE_DELETED = "OTA_PACKAGE_DELETED";
    public static final String DEVICE_ATTRIBUTES_UPDATED = "DEVICE_ATTRIBUTES_UPDATED";
    public static final String DEVICE_ATTRIBUTES_DELETED = "DEVICE_ATTRIBUTES_DELETED";
    public static final String DEVICE_RPC_SENT = "DEVICE_RPC_SENT";
    public static final String DEVICE_RPC_DELETED = "DEVICE_RPC_DELETED";
    public static final String DEVICE_TELEMETRY_UPDATED = "DEVICE_TELEMETRY_UPDATED";
    public static final String DEVICE_TELEMETRY_DELETED = "DEVICE_TELEMETRY_DELETED";
    public static final String NOTIFICATION_READ = "NOTIFICATION_READ";
    public static final String NOTIFICATION_UPDATED = "NOTIFICATION_UPDATED";
    public static final String NOTIFICATION_DELETED = "NOTIFICATION_DELETED";
    public static final String NOTIFICATION_SENT = "NOTIFICATION_SENT";
    public static final String QUEUE_CREATED = "QUEUE_CREATED";
    public static final String QUEUE_UPDATED = "QUEUE_UPDATED";
    public static final String QUEUE_DELETED = "QUEUE_DELETED";
    public static final String TENANT_PROFILE_CREATED = "TENANT_PROFILE_CREATED";
    public static final String TENANT_PROFILE_UPDATED = "TENANT_PROFILE_UPDATED";
    public static final String TENANT_PROFILE_DELETED = "TENANT_PROFILE_DELETED";
    public static final String TENANT_PROFILE_DEFAULT_SWITCHED = "TENANT_PROFILE_DEFAULT_SWITCHED";
    public static final String USER_SETTINGS_UPDATED = "USER_SETTINGS_UPDATED";
    public static final String USER_SETTINGS_DELETED = "USER_SETTINGS_DELETED";
    public static final String USER_ACTIVATION_MAIL_SENT = "USER_ACTIVATION_MAIL_SENT";
    public static final String USER_CREDENTIALS_UPDATED = "USER_CREDENTIALS_UPDATED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_PROBE = "PASSWORD_RESET_PROBE";
    public static final String MFA_CODE_SENT = "MFA_CODE_SENT";
    public static final String DEVICE_PROVISIONED = "DEVICE_PROVISIONED";
    public static final String DEVICE_CLAIM_REGISTERED = "DEVICE_CLAIM_REGISTERED";
}
