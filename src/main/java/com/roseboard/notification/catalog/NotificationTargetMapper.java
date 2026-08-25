package com.roseboard.notification.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import java.util.Arrays;

import java.util.List;
import java.util.UUID;

@Mapper
public interface NotificationTargetMapper extends BaseMapper<NotificationTargetEntity> {
    default List<NotificationTargetEntity> selectByTenant(UUID tenantId) {
        return selectList(new LambdaQueryWrapper<NotificationTargetEntity>()
                .eq(NotificationTargetEntity::getTenantId, tenantId)
                .orderByAsc(NotificationTargetEntity::getName));
    }

    default NotificationTargetEntity selectByTenantAndId(UUID tenantId, UUID id) {
        return selectOne(new LambdaQueryWrapper<NotificationTargetEntity>()
                .eq(NotificationTargetEntity::getTenantId, tenantId)
                .eq(NotificationTargetEntity::getId, id));
    }

    default List<NotificationTargetEntity> selectByTenantAndIds(UUID tenantId, UUID[] ids) {
        return selectList(new LambdaQueryWrapper<NotificationTargetEntity>()
                .eq(NotificationTargetEntity::getTenantId, tenantId)
                .in(NotificationTargetEntity::getId, Arrays.asList(ids))
                .orderByAsc(NotificationTargetEntity::getName));
    }
}
