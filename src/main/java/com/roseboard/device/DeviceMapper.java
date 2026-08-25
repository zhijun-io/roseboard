package com.roseboard.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface DeviceMapper extends BaseMapper<DeviceEntity> {

    default Page<DeviceEntity> selectPage(long pageSize, long page, String textSearch,
                                          String type, UUID deviceProfileId) {
        return selectPage(new Page<>(page + 1L, pageSize), filteredQuery(textSearch, type, deviceProfileId));
    }

    default Page<DeviceEntity> selectPageByTenant(UUID tenantId, long pageSize, long page,
                                                   String textSearch, String type, UUID deviceProfileId) {
        return selectPage(new Page<>(page + 1L, pageSize),
                filteredQuery(textSearch, type, deviceProfileId).eq(DeviceEntity::getTenantId, tenantId));
    }

    default Page<DeviceEntity> selectPageByCustomer(UUID customerId, long pageSize, long page,
                                                     String textSearch, String type, UUID deviceProfileId) {
        return selectPage(new Page<>(page + 1L, pageSize),
                filteredQuery(textSearch, type, deviceProfileId).eq(DeviceEntity::getCustomerId, customerId));
    }

    private static LambdaQueryWrapper<DeviceEntity> filteredQuery(String textSearch, String type,
                                                                  UUID deviceProfileId) {
        return baseQuery(textSearch)
                .eq(type != null && !type.isBlank(), DeviceEntity::getType, type)
                .eq(deviceProfileId != null, DeviceEntity::getDeviceProfileId, deviceProfileId);
    }

    private static LambdaQueryWrapper<DeviceEntity> baseQuery(String textSearch) {
        return new LambdaQueryWrapper<DeviceEntity>()
                .like(textSearch != null && !textSearch.isBlank(), DeviceEntity::getName, textSearch)
                .orderByAsc(DeviceEntity::getName);
    }

    default DeviceEntity findByTenantAndName(UUID tenantId, String name) {
        return selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getTenantId, tenantId)
                .eq(DeviceEntity::getName, name));
    }
}
