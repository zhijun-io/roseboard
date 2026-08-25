package com.roseboard.security.authorization;

import com.roseboard.user.PermissionChecker;
import com.roseboard.user.UserAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckerTest {

    private final PermissionChecker permissionChecker = new PermissionChecker();

    @Test
    void systemAdminHasEveryPermission() {
        var authentication = authentication(UserAuthority.SYS_ADMIN.name());

        assertTrue(permissionChecker.hasPermission(authentication, "ADMIN_SETTINGS", "WRITE"));
    }

    @Test
    void tenantAdminGetsConfiguredManagementPermissions() {
        var authentication = authentication(UserAuthority.TENANT_ADMIN.name());

        assertTrue(permissionChecker.hasPermission(authentication, "CUSTOMER", "DELETE"));
        assertTrue(permissionChecker.hasPermission(authentication, "TENANT", "READ"));
        assertFalse(permissionChecker.hasPermission(authentication, "TENANT", "DELETE"));
    }

    @Test
    void customerUserIsReadOnlyForCustomerResources() {
        var authentication = authentication("CUSTOMER_USER");

        assertTrue(permissionChecker.hasPermission(authentication, "DEVICE", "READ"));
        assertFalse(permissionChecker.hasPermission(authentication, "DEVICE", "WRITE"));
    }

    @Test
    void invalidPermissionInputIsRejected() {
        var authentication = authentication(UserAuthority.SYS_ADMIN.name());

        assertFalse(permissionChecker.hasPermission(authentication, null, "READ"));
        assertFalse(permissionChecker.hasPermission(authentication, "USER", null));
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority(authority)));
    }
}
