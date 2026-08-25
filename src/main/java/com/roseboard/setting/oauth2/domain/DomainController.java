package com.roseboard.setting.oauth2.domain;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.event.EntityType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 域名配置接口：管理域名及其 OAuth2 Client 关联。
 */
@RestController
@RequestMapping("/api/domains")
public class DomainController {
    private final DomainService service;

    public DomainController(DomainService service) {
        this.service = service;
    }

    /**
     * 创建或更新资源。
     */


    @PostMapping
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = "#domain.id != null ? T(com.roseboard.audit.AuditActions).DOMAIN_UPDATED : T(com.roseboard.audit.AuditActions).DOMAIN_CREATED",
            entityType = EntityType.DOMAIN, entityId = "#result.id", entityName = "#result.name")
    public DomainEntity save(@RequestBody DomainEntity domain, @RequestParam(required = false) UUID[] oauth2ClientIds) {
        return service.save(domain, oauth2ClientIds);
    }

    /**
     * 更新资源。
     */
    @PutMapping("/{domainId}")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = AuditActions.DOMAIN_UPDATED, entityType = EntityType.DOMAIN,
            entityId = "#domainId", entityName = "#result.name")
    public DomainEntity update(@PathVariable UUID domainId, @RequestBody DomainEntity domain,
                               @RequestParam(required = false) UUID[] oauth2ClientIds) {
        domain.setId(domainId);
        return service.save(domain, oauth2ClientIds);
    }

    /**
     * 处理 /{id}/oauth2-clients 对应的接口请求。
     */
    @PutMapping("/{domainId}/oauth2-clients")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = AuditActions.DOMAIN_UPDATED, entityType = EntityType.DOMAIN,
            entityId = "#domainId")
    public void updateOauth2Clients(@PathVariable UUID domainId, @RequestBody UUID[] clientIds) {
        service.updateOauth2Clients(domainId, clientIds);
    }

    /**
     * 分页查询资源摘要。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    public PageData<DomainEntity> infos(@RequestParam long pageSize, @RequestParam long page,
                                        @RequestParam(required = false) String textSearch) {
        return service.findPage(pageSize, page, textSearch);
    }

    /**
     * 查询资源摘要信息。
     */
    @GetMapping("/{domainId}")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    public DomainEntity info(@PathVariable UUID domainId) {
        return service.requireEnrichedById(domainId);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{domainId}")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = AuditActions.DOMAIN_DELETED, entityType = EntityType.DOMAIN,
            entityId = "#domainId")
    public void delete(@PathVariable UUID domainId) {
        service.delete(domainId);
    }
}
