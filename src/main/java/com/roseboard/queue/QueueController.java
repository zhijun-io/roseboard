package com.roseboard.queue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 队列管理接口：提供队列查询、创建、更新和删除。
 */
@RestController
@RequestMapping("/api")
public class QueueController {
    private final QueueService queueService;
    private final DataScopeAuthorizer dataScopeService;

    public QueueController(QueueService queueService,
                           DataScopeAuthorizer dataScopeService) {
        this.queueService = queueService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 分页查询资源。
     */
    @GetMapping("/queues")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public PageData<QueueView> list(@RequestParam long pageSize, @RequestParam long page,
                                    @RequestParam(required = false) String textSearch,
                                    @RequestParam(required = false) UUID tenantId,
                                    Authentication authentication) {
        UUID scopeTenant = resolveTenant(authentication, tenantId);
        return mapPage(queueService.findPage(scopeTenant, pageSize, page, textSearch), pageSize, page);
    }

    /**
     * 按队列 ID 查询队列。
     */
    @GetMapping("/queues/{queueId}")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public QueueView getById(@PathVariable UUID queueId,
                             @RequestParam(required = false) UUID tenantId,
                             Authentication authentication) {
        UUID scopeTenant = resolveTenant(authentication, tenantId);
        return queueService.findById(scopeTenant, queueId)
                .map(QueueView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue not found"));
    }

    /**
     * 按队列名称查询队列。
     */
    @GetMapping(value = "/queues", params = "name")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public QueueView getByName(@RequestParam("name") String queueName,
                               @RequestParam(required = false) UUID tenantId,
                               Authentication authentication) {
        UUID scopeTenant = resolveTenant(authentication, tenantId);
        return queueService.findByName(scopeTenant, queueName)
                .map(QueueView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue not found"));
    }

    /**
     * 创建或更新资源。
     */
    @PostMapping("/queues")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.WRITE)
    @Audited(action = "#body.id() == null ? T(com.roseboard.audit.AuditActions).QUEUE_CREATED : T(com.roseboard.audit.AuditActions).QUEUE_UPDATED",
            entityType = EntityType.QUEUE, entityId = "#result.id()")
    public QueueView save(@Valid @RequestBody QueueView body, Authentication authentication) {
        dataScopeService.requireSystemAdmin(authentication);
        try {
            if (body.id() == null) {
                return QueueView.from(queueService.createManaged(body.toDefinition()));
            }
            return QueueView.from(queueService.updateManaged(
                    body.tenantId(), body.id(), body.toDefinition()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/queues/{queueId}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.DELETE)
    @Audited(action = AuditActions.QUEUE_DELETED, entityType = EntityType.QUEUE, entityId = "#queueId")
    public void delete(@PathVariable UUID queueId,
                       @RequestParam UUID tenantId,
                       Authentication authentication) {
        dataScopeService.requireSystemAdmin(authentication);
        try {
            queueService.deleteManaged(tenantId, queueId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private UUID resolveTenant(Authentication authentication, UUID tenantIdParam) {
        SecurityUsers scope = dataScopeService.current(authentication);
        if (scope.isSystemAdmin()) {
            if (tenantIdParam == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
            }
            return tenantIdParam;
        }
        if (scope.getTenantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant scope denied");
        }
        if (tenantIdParam != null && !tenantIdParam.equals(scope.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant scope denied");
        }
        return scope.getTenantId();
    }

    private static PageData<QueueView> mapPage(PageData<QueueDefinition> page, long pageSize, long currentPage) {
        return new PageData<>(page.data().stream().map(QueueView::from).toList(),
                pageSize, currentPage, page.totalElements());
    }

    public record QueueView(
            UUID id,
            Long createdTime,
            @NotNull UUID tenantId,
            String name,
            String topic,
            Integer pollInterval,
            Integer partitions,
            Boolean consumerPerPartition,
            Long packProcessingTimeout,
            SubmitStrategy submitStrategy,
            ProcessingStrategy processingStrategy,
            JsonNode additionalInfo
    ) {
        static QueueView from(QueueDefinition definition) {
            return new QueueView(
                    definition.id(),
                    definition.createdTime(),
                    definition.tenantId(),
                    definition.name(),
                    definition.topic(),
                    definition.pollInterval(),
                    definition.partitions(),
                    definition.consumerPerPartition(),
                    definition.packProcessingTimeout(),
                    definition.submitStrategy(),
                    definition.processingStrategy(),
                    definition.additionalInfo());
        }

        QueueDefinition toDefinition() {
            return QueueDefinition.builder()
                    .id(id)
                    .createdTime(createdTime == null ? 0L : createdTime)
                    .tenantId(tenantId)
                    .name(name)
                    .topic(topic)
                    .pollInterval(pollInterval == null ? 25 : pollInterval)
                    .partitions(partitions == null ? 10 : partitions)
                    .consumerPerPartition(Boolean.TRUE.equals(consumerPerPartition))
                    .packProcessingTimeout(packProcessingTimeout == null ? 2000L : packProcessingTimeout)
                    .submitStrategy(submitStrategy)
                    .processingStrategy(processingStrategy)
                    .additionalInfo(additionalInfo)
                    .build();
        }
    }
}
