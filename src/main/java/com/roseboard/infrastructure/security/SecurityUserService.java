package com.roseboard.infrastructure.security;

import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.UUID;

/** Host seam for loading an authenticated principal. */
public interface SecurityUserService extends UserDetailsService {
    @Override
    SecurityUser loadUserByUsername(String username);

    SecurityUser loadUserById(UUID userId);
}
