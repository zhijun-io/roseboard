package com.roseboard.notification.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface NotificationChannelConfigMapper extends BaseMapper<NotificationChannelConfigEntity> {
    default NotificationChannelConfigEntity selectScope(UUID tenantId, String channelKind) {
        LambdaQueryWrapper<NotificationChannelConfigEntity> query = new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                .eq(NotificationChannelConfigEntity::getChannelKind, channelKind);
        if (tenantId == null) {
            query.isNull(NotificationChannelConfigEntity::getTenantId);
        } else {
            query.eq(NotificationChannelConfigEntity::getTenantId, tenantId);
        }
        return selectOne(query);
    }
}
