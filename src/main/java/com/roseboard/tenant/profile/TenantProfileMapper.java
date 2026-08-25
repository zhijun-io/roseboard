package com.roseboard.tenant.profile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Mapper
public interface TenantProfileMapper extends BaseMapper<TenantProfileEntity> {
    default Page<TenantProfileEntity> selectPage(long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<TenantProfileEntity>()
                        .like(textSearch != null && !textSearch.isBlank(),
                                TenantProfileEntity::getName, textSearch)
                        .orderByAsc(TenantProfileEntity::getName));
    }

    default int insertProfile(UUID id, Long createdTime, String name, String description,
                              Boolean isDefault, Boolean isolatedTbCore, Boolean isolatedTbRuleEngine) {
        return insertProfile(id, createdTime, name, description, isDefault, isolatedTbCore,
                isolatedTbRuleEngine, null);
    }

    default int insertProfile(UUID id, Long createdTime, String name, String description,
                              Boolean isDefault, Boolean isolatedTbCore, Boolean isolatedTbRuleEngine,
                              JsonNode profileData) {
        TenantProfileEntity profile = new TenantProfileEntity();
        profile.setId(id);
        profile.setCreatedTime(createdTime);
        profile.setName(name);
        profile.setDescription(description);
        profile.setIsDefault(isDefault);
        profile.setIsolatedTbCore(isolatedTbCore);
        profile.setIsolatedTbRuleEngine(isolatedTbRuleEngine);
        profile.setProfileData(TenantProfileData.normalize(profileData));
        return insert(profile);
    }
}
