package com.roseboard.queue.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class QueueStatsService {
    private final QueueStatsMapper mapper;

    public QueueStatsService(QueueStatsMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public QueueStats save(QueueStats stats) {
        QueueStatsEntity entity = new QueueStatsEntity();
        entity.setId(stats.id() == null ? UUID.randomUUID() : stats.id());
        entity.setCreatedTime(stats.createdTime() > 0 ? stats.createdTime() : System.currentTimeMillis());
        entity.setTenantId(stats.tenantId());
        entity.setQueueName(stats.queueName());
        entity.setServiceId(stats.serviceId());
        mapper.insert(entity);
        return toDomain(entity);
    }

    public Optional<QueueStats> findByTenantIdAndQueueNameAndServiceId(UUID tenantId, String queueName, String serviceId) {
        QueueStatsEntity entity = mapper.selectOne(new LambdaQueryWrapper<QueueStatsEntity>()
                .eq(QueueStatsEntity::getTenantId, tenantId)
                .eq(QueueStatsEntity::getQueueName, queueName)
                .eq(QueueStatsEntity::getServiceId, serviceId));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    public List<QueueStats> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<QueueStatsEntity>()
                        .in(QueueStatsEntity::getId, ids))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    public List<QueueStats> findByTenantId(UUID tenantId) {
        return mapper.selectList(new LambdaQueryWrapper<QueueStatsEntity>()
                        .eq(QueueStatsEntity::getTenantId, tenantId)
                        .orderByAsc(QueueStatsEntity::getQueueName))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Transactional
    public int deleteByTenantId(UUID tenantId) {
        return mapper.delete(new LambdaQueryWrapper<QueueStatsEntity>()
                .eq(QueueStatsEntity::getTenantId, tenantId));
    }

    private QueueStats toDomain(QueueStatsEntity entity) {
        return new QueueStats(entity.getId(), entity.getCreatedTime(), entity.getTenantId(),
                entity.getQueueName(), entity.getServiceId());
    }
}
