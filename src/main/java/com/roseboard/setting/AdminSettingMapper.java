package com.roseboard.setting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import static com.roseboard.common.Constants.SYSTEM_TENANT_ID;

@Mapper
public interface AdminSettingMapper extends BaseMapper<AdminSettingEntity> {

    default AdminSettingEntity findByKey(String key) {
        return this.selectOne(new LambdaQueryWrapper<AdminSettingEntity>().eq(AdminSettingEntity::getTenantId, SYSTEM_TENANT_ID).eq(AdminSettingEntity::getKey, key));
    }

}
