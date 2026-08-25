package com.roseboard.setting.oauth2.template;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

/**
 * OAuth2 配置模板接口：管理 OAuth2 Provider 配置模板。
 */
@RestController
@RequestMapping("/api/oauth2/template")
public class OAuth2TemplateController {
    private final OAuth2TemplateMapper mapper;
    public OAuth2TemplateController(OAuth2TemplateMapper mapper) { this.mapper = mapper; }

    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = "#template.id == null ? T(com.roseboard.audit.AuditActions).OAUTH2_TEMPLATE_CREATED : T(com.roseboard.audit.AuditActions).OAUTH2_TEMPLATE_UPDATED",
            entityType = EntityType.ADMIN_SETTINGS, entityId = "#result.id")
    public OAuth2TemplateEntity save(@RequestBody OAuth2TemplateEntity template) {
        if (template.getId() == null) {
            template.setId(UUID.randomUUID());
            template.setCreatedTime(System.currentTimeMillis());
            mapper.insert(template);
        } else if (mapper.selectById(template.getId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 template not found");
        } else {
            mapper.updateById(template);
        }
        return mapper.selectById(template.getId());
    }

    /**
     * 删除资源。
     */
/**
 * 创建或更新资源。
 */

    @DeleteMapping("/{templateId}")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @Audited(action = AuditActions.OAUTH2_TEMPLATE_DELETED, entityType = EntityType.ADMIN_SETTINGS,
            entityId = "#templateId")
    public void delete(@PathVariable UUID templateId) {
        if (mapper.deleteById(templateId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 template not found");
    }

    /**
     * 分页查询资源。
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN')")
    public List<OAuth2TemplateEntity> list() { return mapper.selectList(null); }
}
