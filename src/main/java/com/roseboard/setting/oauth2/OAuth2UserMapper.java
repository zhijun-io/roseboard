package com.roseboard.setting.oauth2;

import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.user.UserEntity;

import java.util.Map;

public interface OAuth2UserMapper {
    String email(Map<String, Object> userInfo, OAuth2ClientEntity client);
    UserEntity findExisting(Map<String, Object> userInfo, OAuth2ClientEntity client);
}
