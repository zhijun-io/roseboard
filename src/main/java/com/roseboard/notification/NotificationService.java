package com.roseboard.notification;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.notification.spi.NotificationInboxWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationService implements NotificationInboxWriter {
    private final NotificationMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(NotificationMapper mapper, ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void save(UUID scopeId, UUID recipientId, String type, String subject, String body) {
        save(scopeId, recipientId, type, subject, body, "WEB");
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(UUID scopeId, UUID recipientId, String type, String subject, String body,
                     String deliveryMethod) {
        NotificationEntity entity = new NotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setCreatedTime(System.currentTimeMillis());
        entity.setScopeId(scopeId);
        entity.setRecipientId(recipientId);
        entity.setType(type);
        entity.setSubject(subject == null ? "" : subject);
        entity.setBody(body == null ? "" : body);
        entity.setDeliveryMethod(deliveryMethod == null ? "WEB" : deliveryMethod);
        entity.setStatus("UNREAD");
        mapper.insert(entity);
        eventPublisher.publishEvent(new NotificationSavedEvent(recipientId, entity));
    }

    public PageData<NotificationEntity> page(UUID recipientId, long pageSize, long page, boolean unreadOnly) {
        return page(recipientId, pageSize, page, null, "WEB", unreadOnly);
    }

    public PageData<NotificationEntity> page(UUID recipientId, long pageSize, long page,
                                              String textSearch, String deliveryMethod, boolean unreadOnly) {
        Page<NotificationEntity> result = mapper.selectPageByRecipient(recipientId, pageSize, page,
                textSearch, deliveryMethod == null ? "WEB" : deliveryMethod, unreadOnly);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    public long countUnread(UUID recipientId) {
        return mapper.countUnread(recipientId, "WEB");
    }

    public long countUnread(UUID recipientId, String deliveryMethod) {
        return mapper.countUnread(recipientId, deliveryMethod == null ? "WEB" : deliveryMethod);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID recipientId, UUID notificationId) {
        NotificationEntity entity = mapper.selectById(notificationId);
        if (entity == null || !recipientId.equals(entity.getRecipientId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        mapper.deleteById(notificationId);
    }


    public List<NotificationEntity> latestUnread(UUID recipientId, int limit, Collection<String> types) {
        int effectiveLimit = limit > 0 ? limit : 10;
        return mapper.selectLatestUnread(recipientId, effectiveLimit, types == null ? Set.of() : types);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(UUID recipientId, UUID notificationId) {
        NotificationEntity entity = mapper.selectById(notificationId);
        if (entity == null || !recipientId.equals(entity.getRecipientId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        if ("READ".equals(entity.getStatus())) {
            return;
        }
        entity.setStatus("READ");
        entity.setReadTime(System.currentTimeMillis());
        mapper.updateById(entity);
        eventPublisher.publishEvent(new NotificationReadEvent(recipientId, notificationId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(UUID recipientId) {
        markAllRead(recipientId, "WEB");
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(UUID recipientId, String deliveryMethod) {
        mapper.markAllRead(recipientId,
                deliveryMethod == null ? "WEB" : deliveryMethod,
                System.currentTimeMillis());
        eventPublisher.publishEvent(new NotificationReadEvent(recipientId, null, true));
    }
}
