package com.roseboard.device.attribute;

import com.roseboard.user.UserAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributeAuthorizationServiceTest {
    private final AttributeAuthorizationService policy = new AttributeAuthorizationService();

    @Test
    void deviceCanReadClientAndSharedButNotWriteShared() {
        var device = new UsernamePasswordAuthenticationToken("device", "n/a",
                java.util.List.of(new SimpleGrantedAuthority(
                        AttributeAuthorizationService.DEVICE_AUTHENTICATED)));
        assertDoesNotThrow(() -> policy.requireRead(device, AttributeScope.CLIENT));
        assertDoesNotThrow(() -> policy.requireRead(device, AttributeScope.SHARED));
        assertThrows(AccessDeniedException.class,
                () -> policy.requireWrite(device, AttributeScope.SHARED));
        assertThrows(AccessDeniedException.class,
                () -> policy.requireRead(device, AttributeScope.SERVER));
    }

    @Test
    void serverCanReadAndWriteAllScopes() {
        var server = new UsernamePasswordAuthenticationToken("admin", "n/a",
                java.util.List.of(new SimpleGrantedAuthority(UserAuthority.TENANT_ADMIN.name())));
        for (AttributeScope scope : AttributeScope.values()) {
            assertDoesNotThrow(() -> policy.requireRead(server, scope));
            assertDoesNotThrow(() -> policy.requireWrite(server, scope));
        }
    }
}
