package com.roseboard.user.setting;

import com.roseboard.common.JacksonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

@Service
public class UserSettingService {
    public static final String GENERAL = "general";
    public static final String MFA = "mfa";
    private static final String DEFAULT_GENERAL = "{\"openedMenuSections\":[]}";
    private final UserSettingMapper mapper;

    public UserSettingService(UserSettingMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode get(UUID userId, String key) {
        JsonNode value = mapper.findValue(userId, key);
        return value == null && GENERAL.equals(key)
                ? JacksonUtils.toJsonNode(DEFAULT_GENERAL) : value;
    }
    @Transactional(rollbackFor = Exception.class)
    public String save(UUID userId, String key, String value) {
        JsonNode jsonValue = value == null ? JacksonUtils.objectMapper().createObjectNode() :
                JacksonUtils.objectMapper().readTree(value);
        mapper.upsert(userId, key == null ? GENERAL : key, jsonValue.toString());
        return jsonValue.toString();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID userId, String key) {
        mapper.delete(userId, key == null ? GENERAL : key);
    }
}
