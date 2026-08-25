package com.roseboard.infrastructure.security.authorization;

import com.roseboard.infrastructure.security.SecurityUserResolver;
import com.roseboard.user.UserAuthority;
import com.roseboard.common.security.SecurityUsers;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class SecurityUserResolverTest {

    private final SecurityUserResolver resolver = new SecurityUserResolver();

    @Test
    void invalidUuidClaimProducesAnonymousIdentity() {
        Authentication authentication = mock(Authentication.class);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "not-a-uuid")
                .build();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(authentication.getAuthorities()).thenReturn(List.of());

        SecurityUsers identity = resolver.resolve(authentication);

        assertNull(identity.getUserId());
        assertNull(identity.getTenantId());
        assertNull(identity.getCustomerId());
        assertNull(identity.getUserName());
    }

    @Test
    void resolvesJwtIdentityAndSystemAdminAuthority() {
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("admin@example.com")
                .claim("userId", userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("customerId", customerId.toString())
                .build();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(jwt);
        doReturn(List.<GrantedAuthority>of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(UserAuthority.SYS_ADMIN.name())))
                .when(authentication).getAuthorities();

        SecurityUsers identity = resolver.resolve(authentication);

        assertEquals(userId, identity.getUserId());
        assertEquals(tenantId, identity.getTenantId());
        assertEquals(customerId, identity.getCustomerId());
        assertEquals("admin@example.com", identity.getUserName());
        assertTrue(identity.isSystemAdmin());
    }

    @Test
    void missingOptionalJwtClaimsProducesPartialIdentity() {
        Authentication authentication = mock(Authentication.class);
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user@example.com")
                .claim("userId", userId.toString())
                .build();
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(authentication.getAuthorities()).thenReturn(List.of());

        SecurityUsers identity = resolver.resolve(authentication);

        assertEquals(userId, identity.getUserId());
        assertNull(identity.getTenantId());
        assertNull(identity.getCustomerId());
        assertEquals("user@example.com", identity.getUserName());
    }

    @Test
    void resolvesNamedPrincipalWithoutSecurityScope() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("batch-user");

        SecurityUsers identity = resolver.resolve(authentication);

        assertNull(identity.getUserId());
        assertEquals("batch-user", identity.getUserName());
        assertFalse(identity.isSystemAdmin());
    }
}
