package com.roseboard.device.attribute;
import com.roseboard.user.UserAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AttributeAuthorizationService {
    public static final String DEVICE_AUTHENTICATED = "DEVICE_AUTHENTICATED";
    private static final Set<String> SERVER_AUTHORITIES = Set.of(UserAuthority.SYS_ADMIN.name(), UserAuthority.TENANT_ADMIN.name());

    public void requireRead(Authentication authentication, AttributeScope scope) {
        if (!allowed(authentication, scope, false)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Attribute read scope denied: " + scope);
        }
    }

    public void requireWrite(Authentication authentication, AttributeScope scope) {
        if (!allowed(authentication, scope, true)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Attribute write scope denied: " + scope);
        }
    }

    private boolean allowed(Authentication authentication, AttributeScope scope, boolean write) {
        if (authentication == null || !authentication.isAuthenticated() || scope == null) {
            return false;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .collect(Collectors.toUnmodifiableSet());
        boolean device = authorities.contains(DEVICE_AUTHENTICATED);
        boolean server = authorities.stream().anyMatch(authority ->
                SERVER_AUTHORITIES.contains(authority)
                        || SERVER_AUTHORITIES.contains(authority.replaceFirst("^ROLE_", "")));
        return switch (scope) {
            case CLIENT -> device || server;
            case SHARED -> write ? server : device || server;
            case SERVER -> server;
        };
    }
}
