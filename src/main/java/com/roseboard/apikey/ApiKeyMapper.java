package com.roseboard.apikey;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyEntity> {
    default ApiKeyEntity findEnabledByHash(String keyHash) {
        return selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyHash, keyHash)
                .eq(ApiKeyEntity::getEnabled, true));
    }
}
