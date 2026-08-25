package com.roseboard.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Service
public class AuditLogService {

    private static final int MAX_ACTOR_NAME_PREFIX = 255;

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public PageData<AuditLogEntity> findPage(Query query, SecurityUsers scope) {
        validate(query);
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<AuditLogEntity>()
                .ge(query.startTime() != null, AuditLogEntity::getOccurredAt, toInstant(query.startTime()))
                .le(query.endTime() != null, AuditLogEntity::getOccurredAt, toInstant(query.endTime()))
                .eq(query.actorUserId() != null, AuditLogEntity::getActorUserId, query.actorUserId())
                .eq(query.customerId() != null, AuditLogEntity::getCustomerId, query.customerId())
                .eq(query.tenantId() != null, AuditLogEntity::getTenantId, query.tenantId())
                .likeRight(query.actorNamePrefix() != null, AuditLogEntity::getActorName, query.actorNamePrefix())
                .eq(query.status() != null, AuditLogEntity::getStatus, query.status())
                .eq(query.origin() != null, AuditLogEntity::getOrigin, query.origin())
                .eq(query.requestId() != null, AuditLogEntity::getRequestId, query.requestId())
                .in(query.actionTypes() != null && query.actionTypes().length > 0,
                        AuditLogEntity::getAction,
                        query.actionTypes() == null ? List.of() : Arrays.asList(query.actionTypes()))
                .orderByDesc(AuditLogEntity::getOccurredAt)
                .orderByDesc(AuditLogEntity::getId);
        if (query.entityType() != null) {
            wrapper.eq(AuditLogEntity::getEntityType, query.entityType())
                    .eq(AuditLogEntity::getEntityId, query.entityId());
        }
        if (!scope.isSystemAdmin()) {
            if (scope.getCustomerId() == null && scope.getTenantId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audit scope denied");
            }
            wrapper.eq(scope.getCustomerId() != null, AuditLogEntity::getCustomerId, scope.getCustomerId())
                    .eq(scope.getCustomerId() == null, AuditLogEntity::getTenantId, scope.getTenantId());
        }
        Page<AuditLogEntity> result = auditLogMapper.selectPage(
                new Page<>(query.page() + 1L, query.pageSize()), wrapper);
        return new PageData<>(result.getRecords(), query.pageSize(), query.page(), result.getTotal());
    }

    private static void validate(Query query) {
        if (query.pageSize() < 1 || query.pageSize() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pageSize must be between 1 and 100");
        }
        if (query.page() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative");
        }
        if (query.startTime() != null && query.endTime() != null
                && query.startTime() > query.endTime()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must not exceed endTime");
        }
        if (query.entityType() != null && query.entityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId is required with entityType");
        }
        if (query.actionTypes() != null && Arrays.stream(query.actionTypes())
                .anyMatch(action -> action == null || action.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actionTypes must not contain blanks");
        }
        if (query.actorNamePrefix() != null
                && query.actorNamePrefix().length() > MAX_ACTOR_NAME_PREFIX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actorName prefix too long");
        }
        if (query.tenantId() != null && query.customerId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenantId and customerId filters are mutually exclusive");
        }
    }

    private static Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    public record Query(
            long pageSize,
            long page,
            Long startTime,
            Long endTime,
            String[] actionTypes,
            AuditStatus status,
            UUID actorUserId,
            String actorNamePrefix,
            UUID tenantId,
            UUID customerId,
            AuditOrigin origin,
            String requestId,
            EntityType entityType,
            UUID entityId) {

        public static Query page(long pageSize, long page, Long startTime, Long endTime,
                                 String[] actionTypes, AuditStatus status, AuditOrigin origin,
                                 String requestId, UUID tenantId, String actorNamePrefix) {
            return new Query(pageSize, page, startTime, endTime, actionTypes, status,
                    null, normalizeActorNamePrefix(actorNamePrefix), tenantId, null,
                    origin, requestId, null, null);
        }

        public Query withActor(UUID actorUserId) {
            return scoped(actorUserId, actorNamePrefix(), tenantId(), customerId(), entityType(), entityId());
        }

        public Query withCustomer(UUID customerId) {
            return scoped(actorUserId(), actorNamePrefix(), tenantId(), customerId, entityType(), entityId());
        }

        public Query withEntity(EntityType entityType, UUID entityId) {
            return scoped(actorUserId(), actorNamePrefix(), tenantId(), customerId(), entityType, entityId);
        }

        private Query scoped(UUID actorUserId, String actorNamePrefix, UUID tenantId, UUID customerId,
                             EntityType entityType, UUID entityId) {
            return new Query(pageSize(), page(), startTime(), endTime(), actionTypes(), status(),
                    actorUserId, actorNamePrefix, tenantId, customerId, origin(), requestId(),
                    entityType, entityId);
        }

        private static String normalizeActorNamePrefix(String value) {
            return StringUtils.isBlank(value) ? null : value.trim();
        }
    }
}
