package com.roseboard.user;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Roseboard's role-to-permission policy.
 *
 * <p>This policy belongs to the host application rather than the reusable
 * security infrastructure.</p>
 */
@Service("permissionChecker")
public class PermissionChecker {
    private static final String SYS_ADMIN = "SYS_ADMIN";
    private static final String TENANT_ADMIN = "TENANT_ADMIN";
    private static final String CUSTOMER_USER = "CUSTOMER_USER";

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            TENANT_ADMIN, Set.of(
                    "CUSTOMER_READ", "CUSTOMER_WRITE", "CUSTOMER_DELETE",
                    "DEVICE_READ", "DEVICE_WRITE", "DEVICE_DELETE",
                    "DEVICE_PROFILE_READ", "DEVICE_PROFILE_WRITE", "DEVICE_PROFILE_DELETE",
                    "OTA_PACKAGE_READ", "OTA_PACKAGE_WRITE", "OTA_PACKAGE_DELETE",
                    "QUEUE_READ",
                    "USAGE_READ",
                    "NOTIFICATION_READ", "NOTIFICATION_WRITE",
                    "USER_READ", "USER_WRITE", "USER_DELETE",
                    "TENANT_READ"),
            CUSTOMER_USER, Set.of("CUSTOMER_READ", "DEVICE_READ", "USER_READ", "OTA_PACKAGE_READ"));

    public boolean hasPermission(Authentication authentication, String resource, String action) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String requiredAuthority = permission(resource, action);
        if (requiredAuthority == null) {
            return false;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .collect(Collectors.toUnmodifiableSet());
        if (authorities.contains(SYS_ADMIN) || authorities.contains(requiredAuthority)) {
            return true;
        }

        return authorities.stream()
                .map(ROLE_PERMISSIONS::get)
                .filter(Objects::nonNull)
                .anyMatch(permissions -> permissions.contains(requiredAuthority));
    }

    private String permission(String resource, String action) {
        if (!StringUtils.hasText(resource) || !StringUtils.hasText(action)) {
            return null;
        }
        return resource.trim().toUpperCase(Locale.ROOT)
                + "_" + action.trim().toUpperCase(Locale.ROOT);
    }
}
