package com.roseboard.device.profile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface DeviceProfileMapper extends BaseMapper<DeviceProfileEntity> {
    default Page<DeviceProfileEntity> selectPageByTenant(UUID tenantId, long pageSize, long page) {
        return selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<DeviceProfileEntity>()
                        .eq(DeviceProfileEntity::getTenantId, tenantId)
                        .orderByAsc(DeviceProfileEntity::getName));
    }

    default DeviceProfileEntity findByProvisionDeviceKey(String provisionDeviceKey) {
        return selectOne(new LambdaQueryWrapper<DeviceProfileEntity>()
                .apply("profile_data->>'provisionDeviceKey' = {0}", provisionDeviceKey)
                .last("limit 1"));
    }
}
