package com.roseboard.notification.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.user.UserAuthority;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;

import java.util.List;
import java.util.UUID;

public final class NotificationRecipients {
    private NotificationRecipients() {
    }

    public static List<UserEntity> tenantAdminUsers(UserMapper userMapper, UUID tenantId) {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId)
                .eq(UserEntity::getAuthority, UserAuthority.TENANT_ADMIN));
    }

    public static RecipientRef from(UserEntity user) {
        return new RecipientRef(user.getId(), user.getEmail(), user.getPhone());
    }

    public static List<RecipientRef> toRecipients(List<UserEntity> users) {
        return users.stream().map(NotificationRecipients::from).toList();
    }
}
