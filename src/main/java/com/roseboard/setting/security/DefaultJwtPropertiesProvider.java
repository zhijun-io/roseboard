package com.roseboard.setting.security;

import com.roseboard.infrastructure.security.JwtProperties;
import com.roseboard.infrastructure.security.jwt.JwtPropertiesProvider;
import com.roseboard.setting.AdminSettingService;
import com.roseboard.setting.JwtSetting;
import org.springframework.stereotype.Service;

/** Admin-setting adapter for JWT configuration. */
@Service
public class DefaultJwtPropertiesProvider implements JwtPropertiesProvider {
    private final AdminSettingService adminSettingService;

    public DefaultJwtPropertiesProvider(AdminSettingService adminSettingService) {
        this.adminSettingService = adminSettingService;
    }

    @Override
    public JwtProperties get() {
        JwtSetting source = adminSettingService.getJwtSettings();
        JwtProperties properties = new JwtProperties();
        properties.setTokenSigningKey(source.getTokenSigningKey());
        properties.setAccessTokenTtl(source.getAccessTokenTtl());
        properties.setRefreshTokenTtl(source.getRefreshTokenTtl());
        properties.setIssuer(source.getIssuer());
        return properties;
    }
}
