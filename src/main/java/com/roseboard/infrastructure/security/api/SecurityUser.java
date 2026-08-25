package com.roseboard.infrastructure.security.api;

import com.roseboard.common.security.SecurityUsers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Framework-neutral authenticated principal supplied by the host identity provider. */
public final class SecurityUser implements UserDetails, SecurityUsers {
    private static final String SYSTEM_ADMIN = "SYS_ADMIN";

    private final UUID userId;
    private final UUID tenantId;
    private final UUID customerId;
    private final String username;
    private final String password;
    private final String authority;
    private final boolean enabled;
    private final boolean publicUser;

    public SecurityUser(UUID userId, UUID tenantId, UUID customerId,
                        String username, String password, String authority,
                        boolean enabled) {
        this(userId, tenantId, customerId, username, password, authority, enabled, false);
    }

    public SecurityUser(UUID userId, UUID tenantId, UUID customerId,
                        String username, String password, String authority,
                        boolean enabled, boolean publicUser) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.username = username;
        this.password = password;
        this.authority = authority;
        this.enabled = enabled;
        this.publicUser = publicUser;
    }

    public UUID getUserId() { return userId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCustomerId() { return customerId; }
    public String getAuthorityName() { return authority; }
    public boolean isPublicUser() { return publicUser; }
    public boolean isCustomerUser() { return customerId != null; }

    public SecurityUser withPublicUser(boolean value) {
        if (publicUser == value) {
            return this;
        }
        return new SecurityUser(userId, tenantId, customerId, username, password,
                authority, enabled, value);
    }

    @Override
    public String getUserName() { return username; }

    @Override
    public boolean isSystemAdmin() { return SYSTEM_ADMIN.equals(authority); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authority == null ? List.of() : List.of(new SimpleGrantedAuthority(authority));
    }

    @Override
    public String getPassword() { return password; }

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return enabled; }
}
