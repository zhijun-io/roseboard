package com.roseboard.queue.stats;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 队列统计接口：提供队列统计分页、批量和单项查询。
 */
@RestController
@RequestMapping("/api/queue-stats")
public class QueueStatsController {
    private final QueueStatsService statsService;
    private final DataScopeAuthorizer dataScopeService;

    public QueueStatsController(QueueStatsService statsService, DataScopeAuthorizer dataScopeService) {
        this.statsService = statsService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 分页查询资源。
     */


    @GetMapping(params = {"pageSize", "page"})
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public PageData<QueueStats> list(@RequestParam long pageSize, @RequestParam long page,
                                     @RequestParam(required = false) String textSearch,
                                     @RequestParam(required = false) UUID tenantId,
                                     Authentication authentication) {
        UUID scopeTenant = resolveTenant(authentication, tenantId);
        List<QueueStats> all = statsService.findByTenantId(scopeTenant).stream()
                .filter(stats -> textSearch == null || textSearch.isBlank()
                        || stats.queueName().toLowerCase(Locale.ROOT)
                        .contains(textSearch.toLowerCase(Locale.ROOT)))
                .toList();
        int from = Math.toIntExact(Math.min(Math.max(page, 0) * pageSize, all.size()));
        int to = Math.min(from + Math.toIntExact(Math.max(pageSize, 0)), all.size());
        return new PageData<>(all.subList(from, to), pageSize, page, all.size());
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{queueStatsId}")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public QueueStats get(@PathVariable UUID queueStatsId, Authentication authentication) {
        return statsService.findByIds(List.of(queueStatsId)).stream()
                .filter(stats -> allowed(authentication, stats))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue stats not found"));
    }

    /**
     * 处理 ids 对应的接口请求。
     */
    @GetMapping(params = "ids")
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public List<QueueStats> getByIds(@RequestParam UUID[] ids, Authentication authentication) {
        return statsService.findByIds(Arrays.asList(ids)).stream()
                .filter(stats -> allowed(authentication, stats))
                .toList();
    }

    /**
     * 查询全部队列统计。
     */
    @GetMapping
    @RequirePermission(resource = EntityType.QUEUE, operation = Operation.READ)
    public List<QueueStats> listAll(@RequestParam(required = false) UUID tenantId,
                                    Authentication authentication) {
        return statsService.findByTenantId(resolveTenant(authentication, tenantId));
    }

    private boolean allowed(Authentication authentication, QueueStats stats) {
        SecurityUsers scope = dataScopeService.current(authentication);
        return scope.isSystemAdmin() || scope.getTenantId() != null && scope.getTenantId().equals(stats.tenantId());
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
}
