package com.roseboard.infrastructure.security;

import com.roseboard.common.security.SecurityUsers;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 将 Spring Security 的不同认证主体解析为统一的 PrincipalIdentity。
 */
@Component
public class SecurityUserResolver {
    private static final SecurityUsers ANONYMOUS = SecurityUsers.anonymous();

    public SecurityUsers resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ANONYMOUS;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUsers identity) {
            return identity;
        }
        if (principal instanceof Jwt jwt) {
            try {
                return SecurityUsers.of(
                        uuidClaim(jwt, "userId"),
                        uuidClaim(jwt, "tenantId"),
                        uuidClaim(jwt, "customerId"),
                        jwt.getSubject(),
                        isSystemAdmin(authentication));
            } catch (IllegalArgumentException ignored) {
                return ANONYMOUS;
            }
        }
        if (principal instanceof String name && !"anonymousUser".equals(name)) {
            return SecurityUsers.of(null, null, null, name);
        }
        return ANONYMOUS;
    }

    private boolean isSystemAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "SYS_ADMIN".equals(authority.getAuthority()));
    }

    private UUID uuidClaim(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

}
