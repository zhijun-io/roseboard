package com.roseboard.infrastructure.security.oauth2;

import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** Host seam for mapping an OAuth2 provider identity to the framework principal. */
public interface OAuth2SecurityUserProvider {
    SecurityUser resolve(String registrationId, OAuth2User user);
}
