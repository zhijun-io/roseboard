package com.roseboard.setting.oauth2;

import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DefaultOAuth2UserMapper implements OAuth2UserMapper {
    private final UserService userService;

    public DefaultOAuth2UserMapper(UserService userService) { this.userService = userService; }

    @Override
    public String email(Map<String, Object> userInfo, OAuth2ClientEntity client) {
        if (userInfo == null) return null;
        String attribute = client.getUserNameAttributeName() == null ? "email" : client.getUserNameAttributeName();
        Object value = userInfo.get(attribute);
        if (value == null) value = userInfo.get("email");
        return value == null ? null : value.toString();
    }

    @Override
    public UserEntity findExisting(Map<String, Object> userInfo, OAuth2ClientEntity client) {
        String email = email(userInfo, client);
        return email == null ? null : userService.findByEmail(email);
    }
}
