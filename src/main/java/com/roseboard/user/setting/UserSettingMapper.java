package com.roseboard.user.setting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roseboard.common.JacksonUtils;
import org.apache.ibatis.annotations.Mapper;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Mapper
public interface UserSettingMapper extends BaseMapper<UserSettingEntity> {
    default JsonNode findValue(UUID userId, String key) {
        UserSettingEntity entity = selectOne(new LambdaQueryWrapper<UserSettingEntity>()
                .eq(UserSettingEntity::getUserId, userId)
                .eq(UserSettingEntity::getKey, key));
        return entity == null || entity.getValue() == null ? null : entity.getValue();
    }

    default int upsert(UUID userId, String key, String jsonValue) {
        UserSettingEntity existing = selectOne(new LambdaQueryWrapper<UserSettingEntity>()
                .eq(UserSettingEntity::getUserId, userId)
                .eq(UserSettingEntity::getKey, key));
        UserSettingEntity entity = new UserSettingEntity();
        entity.setUserId(userId);
        entity.setKey(key);
        entity.setValue(JacksonUtils.toJsonNode(jsonValue));
        return existing == null ? insert(entity) : update(entity, new LambdaUpdateWrapper<UserSettingEntity>()
                .eq(UserSettingEntity::getUserId, userId)
                .eq(UserSettingEntity::getKey, key));
    }

    default int delete(UUID userId, String key) {
        return delete(new LambdaQueryWrapper<UserSettingEntity>()
                .eq(UserSettingEntity::getUserId, userId)
                .eq(UserSettingEntity::getKey, key));
    }
}
