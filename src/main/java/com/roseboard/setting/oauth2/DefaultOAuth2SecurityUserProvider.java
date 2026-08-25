package com.roseboard.setting.oauth2;

import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.oauth2.OAuth2ResolutionException;
import com.roseboard.infrastructure.security.oauth2.OAuth2SecurityUserProvider;
import com.roseboard.setting.security.DefaultSecurityUserService;
import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
import com.roseboard.user.UserAuthority;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.UUID;

/** Roseboard's database-backed OAuth2 identity mapping and provisioning. */
@Service
public class DefaultOAuth2SecurityUserProvider implements OAuth2SecurityUserProvider {
    private final OAuth2UserMapper userMapper;
    private final OAuth2ClientMapper clientMapper;
    private final UserService userService;
    private final DefaultSecurityUserService identityProvider;

    public DefaultOAuth2SecurityUserProvider(OAuth2UserMapper userMapper,
                                             OAuth2ClientMapper clientMapper,
                                             UserService userService,
                                             DefaultSecurityUserService identityProvider) {
        this.userMapper = userMapper;
        this.clientMapper = clientMapper;
        this.userService = userService;
        this.identityProvider = identityProvider;
    }

    @Override
    public SecurityUser resolve(String registrationId, OAuth2User oauthUser) {
        OAuth2ClientEntity client = clientMapper.selectById(UUID.fromString(registrationId));
        if (client == null) {
            throw new OAuth2ResolutionException("OAuth2 client is not registered", 404);
        }
        UserEntity user = userMapper.findExisting(oauthUser.getAttributes(), client);
        if (user == null && Boolean.TRUE.equals(client.getAllowUserCreation())) {
            String email = userMapper.email(oauthUser.getAttributes(), client);
            if (!StringUtils.hasText(email)) {
                throw new OAuth2ResolutionException("OAuth2 provider did not return an email", 400);
            }
            user = new UserEntity();
            user.setEmail(email);
            user.setTenantId(client.getTenantId());
            user.setAuthority(UserAuthority.CUSTOMER_USER);
            user.setFirstName(String.valueOf(oauthUser.getAttributes().getOrDefault("given_name", "")));
            user.setLastName(String.valueOf(oauthUser.getAttributes().getOrDefault("family_name", "")));
            user = userService.createOAuth2User(user, Boolean.TRUE.equals(client.getActivateUser()));
        } else if (user == null) {
            throw new OAuth2ResolutionException("OAuth2 user is not registered", 403);
        }
        return identityProvider.loadUserByUsername(user.getEmail());
    }

}
