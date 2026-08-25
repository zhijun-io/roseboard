package com.roseboard.setting;

import com.roseboard.common.JacksonUtils;
import com.roseboard.setting.mfa.MfaSetting;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static com.roseboard.common.Constants.*;

@Service
public class AdminSettingService {
    private final AdminSettingMapper mapper;

    public AdminSettingService(AdminSettingMapper mapper) {
        this.mapper = mapper;
    }

    public AdminSettingEntity findByKey(String key) {
        return mapper.findByKey(key);
    }


    public MfaSetting getMfaSettings() {
        return read(MFA_SETTINGS_KEY, MfaSetting.class, new MfaSetting());
    }

    public SecuritySetting getSecuritySettings() {
        return read(SECURITY_SETTINGS_KEY, SecuritySetting.class, new SecuritySetting());
    }

    public JwtSetting getJwtSettings() {
        return read(JWT_SETTINGS_KEY, JwtSetting.class, new JwtSetting());
    }

    @Transactional(rollbackFor = Exception.class)
    public MfaSetting saveMfaSettings(MfaSetting value) {
        return save(MFA_SETTINGS_KEY, value, MfaSetting.class);
    }

    @Transactional(rollbackFor = Exception.class)
    public SecuritySetting saveSecuritySettings(SecuritySetting value) {
        return save(SECURITY_SETTINGS_KEY, value, SecuritySetting.class);
    }

    @Transactional(rollbackFor = Exception.class)
    public JwtSetting saveJwtSettings(JwtSetting value) {
        JwtSetting current = getJwtSettings();
        if (value.getTokenSigningKey() == null || value.getTokenSigningKey().isBlank()) {
            value.setTokenSigningKey(current.getTokenSigningKey());
        }
        if (value.getIssuer() == null) value.setIssuer(current.getIssuer());
        if (value.getAccessTokenTtl() == null) value.setAccessTokenTtl(current.getAccessTokenTtl());
        if (value.getRefreshTokenTtl() == null) value.setRefreshTokenTtl(current.getRefreshTokenTtl());
        return save(JWT_SETTINGS_KEY, value, JwtSetting.class);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminSettingEntity saveRaw(String key, JsonNode value) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting key is required");
        }
        if (value == null || !value.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting value must be an object");
        }
        AdminSettingEntity entity = mapper.findByKey(key);
        boolean created = entity == null;
        if (created) {
            entity = new AdminSettingEntity();
            entity.setId(UUID.randomUUID());
            entity.setTenantId(SYSTEM_TENANT_ID);
            entity.setCreatedTime(System.currentTimeMillis());
            entity.setKey(key);
        }
        entity.setValue(value);
        if (created) mapper.insert(entity);
        else mapper.updateById(entity);
        return entity;
    }

    private <T> T read(String key, Class<T> type, T fallback) {
        AdminSettingEntity entity = mapper.findByKey(key);
        return entity == null || entity.getValue() == null
                ? fallback : JacksonUtils.treeToValue(entity.getValue(), type);
    }

    private <T> T save(String key, T value, Class<T> type) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting value is required");
        }
        return JacksonUtils.treeToValue(saveRaw(key, JacksonUtils.valueToTree(value)).getValue(), type);
    }
}
