package com.roseboard.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    default Page<NotificationEntity> selectPageByRecipient(UUID recipientId, long pageSize, long page,
                                                              String textSearch, String deliveryMethod,
                                                              boolean unreadOnly) {
        LambdaQueryWrapper<NotificationEntity> query = new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, recipientId)
                .orderByDesc(NotificationEntity::getCreatedTime);
        if (unreadOnly) {
            query.eq(NotificationEntity::getStatus, "UNREAD");
        }
        if (textSearch != null && !textSearch.isBlank()) {
            query.and(q -> q.like(NotificationEntity::getSubject, textSearch)
                    .or().like(NotificationEntity::getBody, textSearch));
        }
        if (deliveryMethod != null && !deliveryMethod.isBlank()) {
            query.eq(NotificationEntity::getDeliveryMethod, deliveryMethod);
        }
        return selectPage(new Page<>(page + 1L, pageSize), query);
    }

    default long countUnread(UUID recipientId, String deliveryMethod) {
        LambdaQueryWrapper<NotificationEntity> query = new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, recipientId)
                .eq(NotificationEntity::getStatus, "UNREAD");
        if (deliveryMethod != null && !deliveryMethod.isBlank()) {
            query.eq(NotificationEntity::getDeliveryMethod, deliveryMethod);
        }
        return selectCount(query);
    }

    default int markAllRead(UUID recipientId, long readTime) {
        return markAllRead(recipientId, "WEB", readTime);
    }

    default int markAllRead(UUID recipientId, String deliveryMethod, long readTime) {
        return update(null, new LambdaUpdateWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, recipientId)
                .eq(NotificationEntity::getStatus, "UNREAD")
                .eq(NotificationEntity::getDeliveryMethod, deliveryMethod)
                .set(NotificationEntity::getStatus, "READ")
                .set(NotificationEntity::getReadTime, readTime));
    }


    default List<NotificationEntity> selectLatestUnread(UUID recipientId, int limit, Collection<String> types) {
        LambdaQueryWrapper<NotificationEntity> query = new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientId, recipientId)
                .eq(NotificationEntity::getStatus, "UNREAD")
                .orderByDesc(NotificationEntity::getCreatedTime);
        if (types != null && !types.isEmpty()) {
            query.in(NotificationEntity::getType, types);
        }
        return selectPage(new Page<>(1, limit), query).getRecords();
    }
}
