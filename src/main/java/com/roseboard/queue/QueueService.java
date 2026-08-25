package com.roseboard.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.infrastructure.queue.QueueCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QueueService {
    private static final Pattern QUEUE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");

    private final QueueMapper queueMapper;
    private final QueueCoordinator coordinator;

    public QueueService(QueueMapper queueMapper, QueueCoordinator coordinator) {
        this.queueMapper = queueMapper;
        this.coordinator = coordinator;
    }

    @Transactional
    public QueueDefinition create(QueueDefinition draft) {
        validateData(draft);
        validateCreateUniqueness(draft.tenantId(), draft.name(), draft.topic());
        QueueDefinition toSave = draft.toBuilder()
                .id(draft.id() == null ? UUID.randomUUID() : draft.id())
                .createdTime(draft.createdTime() > 0 ? draft.createdTime() : System.currentTimeMillis())
                .build();
        return persistInsert(toSave);
    }

    @Transactional
    public QueueDefinition findOrCreate(QueueDefinition draft) {
        return findByName(draft.tenantId(), draft.name()).orElseGet(() -> create(draft));
    }

    @Transactional
    public QueueDefinition update(UUID tenantId, UUID id, QueueDefinition draft) {
        QueueDefinition existing = findById(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("Queue with id: " + id + " does not exists!"));
        if (!Objects.equals(existing.name(), draft.name())) {
            throw new IllegalArgumentException("Queue name can't be changed!");
        }
        if (!Objects.equals(existing.topic(), draft.topic())) {
            throw new IllegalArgumentException("Queue topic can't be changed!");
        }
        QueueDefinition toUpdate = draft.toBuilder()
                .id(existing.id())
                .createdTime(existing.createdTime())
                .tenantId(existing.tenantId())
                .name(existing.name())
                .topic(existing.topic())
                .build();
        validateData(toUpdate);
        return persistUpdate(toUpdate);
    }

    @Transactional
    public boolean delete(UUID tenantId, UUID id) {
        return queueMapper.delete(new LambdaQueryWrapper<QueueEntity>()
                .eq(QueueEntity::getId, id)
                .eq(QueueEntity::getTenantId, tenantId)) > 0;
    }

    public QueueDefinition createManaged(QueueDefinition draft) {
        QueueDefinition created = create(draft);
        coordinator.startIfBound(created.toTransportConfig());
        return created;
    }

    public QueueDefinition updateManaged(UUID tenantId, UUID id, QueueDefinition draft) {
        QueueDefinition existing = findById(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("Queue with id: " + id + " does not exists!"));
        QueueDefinition updated = update(tenantId, id, draft);
        try {
            coordinator.restartIfBound(updated.toTransportConfig());
            return updated;
        } catch (RuntimeException restartFailed) {
            update(tenantId, id, existing);
            throw restartFailed;
        }
    }

    public void deleteManaged(UUID tenantId, UUID id) {
        QueueDefinition existing = findById(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("Queue with id: " + id + " does not exists!"));
        coordinator.stopIfBound(existing.name());
        if (!delete(tenantId, id)) {
            coordinator.startIfBound(existing.toTransportConfig());
            throw new IllegalArgumentException("Queue with id: " + id + " does not exists!");
        }
    }

    public Optional<QueueDefinition> findById(UUID tenantId, UUID id) {
        QueueEntity entity = queueMapper.selectOne(new LambdaQueryWrapper<QueueEntity>()
                .eq(QueueEntity::getId, id)
                .eq(QueueEntity::getTenantId, tenantId));
        return Optional.ofNullable(entity).map(QueueConverter::toDefinition);
    }

    public Optional<QueueDefinition> findByName(UUID tenantId, String name) {
        QueueEntity entity = queueMapper.selectOne(new LambdaQueryWrapper<QueueEntity>()
                .eq(QueueEntity::getTenantId, tenantId)
                .eq(QueueEntity::getName, name));
        return Optional.ofNullable(entity).map(QueueConverter::toDefinition);
    }

    public Optional<QueueDefinition> findByTopic(UUID tenantId, String topic) {
        QueueEntity entity = queueMapper.selectOne(new LambdaQueryWrapper<QueueEntity>()
                .eq(QueueEntity::getTenantId, tenantId)
                .eq(QueueEntity::getTopic, topic));
        return Optional.ofNullable(entity).map(QueueConverter::toDefinition);
    }

    public List<QueueDefinition> findAllByTenantId(UUID tenantId) {
        return queueMapper.selectList(new LambdaQueryWrapper<QueueEntity>()
                        .eq(QueueEntity::getTenantId, tenantId))
                .stream()
                .map(QueueConverter::toDefinition)
                .toList();
    }

    public PageData<QueueDefinition> findPage(UUID tenantId, long pageSize, long page, String textSearch) {
        if (pageSize < 1 || page < 0) {
            throw new IllegalArgumentException("Invalid page request");
        }
        List<QueueDefinition> all = findAllByTenantId(tenantId).stream()
                .filter(queue -> textSearch == null || textSearch.isBlank()
                        || queue.name().toLowerCase(Locale.ROOT).contains(textSearch.toLowerCase(Locale.ROOT))
                        || queue.topic().toLowerCase(Locale.ROOT).contains(textSearch.toLowerCase(Locale.ROOT)))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        int from = Math.toIntExact(Math.min(page * pageSize, all.size()));
        int to = Math.min(from + Math.toIntExact(pageSize), all.size());
        return new PageData<>(all.subList(from, to), pageSize, page, all.size());
    }

    private QueueDefinition persistInsert(QueueDefinition definition) {
        QueueEntity entity = QueueConverter.toEntity(definition);
        queueMapper.insert(entity);
        return QueueConverter.toDefinition(entity);
    }

    private QueueDefinition persistUpdate(QueueDefinition definition) {
        QueueEntity entity = QueueConverter.toEntity(definition);
        queueMapper.updateById(entity);
        return QueueConverter.toDefinition(entity);
    }

    private void validateCreateUniqueness(UUID tenantId, String name, String topic) {
        if (findByName(tenantId, name).isPresent()) {
            throw new IllegalArgumentException("Queue with name: " + name + " already exists!");
        }
        if (findByTopic(tenantId, topic).isPresent()) {
            throw new IllegalArgumentException("Queue with topic: " + topic + " already exists!");
        }
    }

    private void validateData(QueueDefinition queue) {
        validateNameOrTopic(queue.name(), "name");
        validateNameOrTopic(queue.topic(), "topic");
        if (queue.pollInterval() < 1) {
            throw new IllegalArgumentException("Queue poll interval should be more then 0!");
        }
        if (queue.partitions() < 1) {
            throw new IllegalArgumentException("Queue partitions should be more then 0!");
        }
        if (queue.packProcessingTimeout() < 1) {
            throw new IllegalArgumentException("Queue pack processing timeout should be more then 0!");
        }
        SubmitStrategy submit = queue.submitStrategy();
        if (submit == null || submit.type() == null) {
            throw new IllegalArgumentException("Queue submit strategy type can't be null!");
        }
        if (submit.type() == SubmitStrategyType.BATCH && submit.batchSize() < 1) {
            throw new IllegalArgumentException("Queue submit strategy batch size should be more then 0!");
        }
        ProcessingStrategy processing = queue.processingStrategy();
        if (processing == null || processing.type() == null) {
            throw new IllegalArgumentException("Queue processing strategy type can't be null!");
        }
        if (processing.retries() < 0) {
            throw new IllegalArgumentException("Queue processing strategy retries can't be less then 0!");
        }
        if (processing.failurePercentage() < 0 || processing.failurePercentage() > 100) {
            throw new IllegalArgumentException(
                    "Queue processing strategy failure percentage should be in a range from 0 to 100!");
        }
        if (processing.pauseBetweenRetries() < 0) {
            throw new IllegalArgumentException(
                    "Queue processing strategy pause between retries can't be less then 0!");
        }
        if (processing.maxPauseBetweenRetries() < processing.pauseBetweenRetries()) {
            throw new IllegalArgumentException(
                    "Queue processing strategy MAX pause between retries can't be less then pause between retries!");
        }
    }

    private static void validateNameOrTopic(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Queue " + fieldName + " should be specified!");
        }
        if (!QUEUE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Queue " + fieldName
                    + " contains a character other than ASCII alphanumerics, '.', '_' and '-'!");
        }
    }
}
