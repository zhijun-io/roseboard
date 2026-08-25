package com.roseboard.setting.oauth2.client;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import java.util.Objects;

/**
 * OAuth2 Client 管理接口：提供 Client 的创建、查询、更新和删除。
 */
@RestController
@RequestMapping("/api/oauth2-clients")
public class OAuth2ClientController {
    private final OAuth2ClientMapper mapper;

    public OAuth2ClientController(OAuth2ClientMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 创建或更新资源。
     */


    @PostMapping
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = "#client.id != null ? T(com.roseboard.audit.AuditActions).OAUTH2_CLIENT_UPDATED : T(com.roseboard.audit.AuditActions).OAUTH2_CLIENT_CREATED",
            entityType = EntityType.OAUTH2_CLIENT, entityId = "#result.id", entityName = "#result.title")
    public OAuth2ClientEntity save(@RequestBody OAuth2ClientEntity client) {
        boolean created = client.getId() == null;
        if (client.getTenantId() == null) {
            client.setTenantId(new UUID(0, 0));
        }
        if (client.getId() == null) {
            client.setId(UUID.randomUUID());
            client.setCreatedTime(System.currentTimeMillis());
            mapper.insert(client);
        } else {
            mapper.updateById(client);
        }
        OAuth2ClientEntity saved = mapper.selectById(client.getId());
        saved.setClientSecret(null);
        return saved;
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{clientId}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public OAuth2ClientEntity get(@PathVariable UUID clientId) {
        OAuth2ClientEntity client = mapper.selectById(clientId);
        if (client == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 client not found");
        client.setClientSecret(null);
        return client;
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{clientId}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.DELETE)
    @Audited(action = AuditActions.OAUTH2_CLIENT_DELETED, entityType = EntityType.OAUTH2_CLIENT,
            entityId = "#clientId")
    public void delete(@PathVariable UUID clientId) {
        mapper.deleteById(clientId);
    }

    /**
     * 分页查询资源。
     */
    @GetMapping(params = {"pageSize", "page"})
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public PageData<OAuth2ClientEntity> list(@RequestParam long pageSize, @RequestParam long page,
                                              @RequestParam(required = false) String textSearch) {
        var result = mapper.selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<OAuth2ClientEntity>()
                        .like(textSearch != null && !textSearch.isBlank(), OAuth2ClientEntity::getTitle, textSearch)
                        .orderByAsc(OAuth2ClientEntity::getTitle));
        result.getRecords().forEach(client -> client.setClientSecret(null));
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    /**
     * 按 ID 批量查询资源。
     */
    @GetMapping(params = "ids")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public List<OAuth2ClientEntity> listByIds(@RequestParam UUID[] ids) {
        return Arrays.stream(ids)
                .map(mapper::selectById)
                .filter(Objects::nonNull)
                .peek(client -> client.setClientSecret(null))
                .toList();
    }
}
