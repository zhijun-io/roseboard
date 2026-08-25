package com.roseboard.audit;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.common.PageData;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.roseboard.infrastructure.security.api.Operation.READ;

/**
 * 审计日志查询接口：按时间、用户、客户和实体筛选审计记录。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {
    private final AuditLogService auditLogService;
    private final DataScopeAuthorizer dataScopeService;

    public AuditLogController(AuditLogService auditLogService, DataScopeAuthorizer dataScopeService) {
        this.auditLogService = auditLogService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping
    @RequirePermission(resource = EntityType.AUDIT_LOG, operation = READ)
    public PageData<AuditLogEntity> logs(@ModelAttribute PageParams params, Authentication authentication) {
        return find(params.toQuery(), authentication);
    }

    @GetMapping("/customer/{customerId}")
    @RequirePermission(resource = EntityType.AUDIT_LOG, operation = READ,
            scope = "@dataScopeAuthorizer.hasCustomerAccess(authentication, #customerId)")
    public PageData<AuditLogEntity> byCustomer(@PathVariable UUID customerId, @ModelAttribute PageParams params,
                                                Authentication authentication) {
        return find(params.toQuery().withCustomer(customerId), authentication);
    }

    @GetMapping("/user/{userId}")
    @RequirePermission(resource = EntityType.AUDIT_LOG, operation = READ,
            scope = "hasAuthority('SYS_ADMIN') or @dataScopeAuthorizer.isCurrentUser(authentication, #userId)")
    public PageData<AuditLogEntity> byUser(@PathVariable UUID userId, @ModelAttribute PageParams params,
                                            Authentication authentication) {
        return find(params.toQuery().withActor(userId), authentication);
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @RequirePermission(resource = EntityType.AUDIT_LOG, operation = READ)
    public PageData<AuditLogEntity> byEntity(@PathVariable EntityType entityType, @PathVariable UUID entityId,
                                              @ModelAttribute PageParams params, Authentication authentication) {
        return find(params.toQuery().withEntity(entityType, entityId), authentication);
    }

    private PageData<AuditLogEntity> find(AuditLogService.Query query, Authentication authentication) {
        return auditLogService.findPage(query, dataScopeService.current(authentication));
    }

    /** 四个查询端点共享的分页与筛选参数。 */
    record PageParams(
            long pageSize,
            long page,
            Long startTime,
            Long endTime,
            String[] actionTypes,
            AuditStatus status,
            AuditOrigin origin,
            String requestId,
            UUID tenantId,
            String actorNamePrefix) {

        AuditLogService.Query toQuery() {
            return AuditLogService.Query.page(pageSize, page, startTime, endTime, actionTypes, status, origin,
                    requestId, tenantId, actorNamePrefix);
        }
    }
}
