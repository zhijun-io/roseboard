package com.roseboard.setting.security;

import com.roseboard.common.security.SecurityUserProvider;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.security.SecurityUserResolver;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;


/**
 * 从 Spring Security 上下文解析当前审计主体。audit 模块只依赖
 * {@link SecurityUserProvider} 接口，不在本实现之外感知 security。
 * <p>
 * 支持两种 web 主体：SecurityPrincipal（form/apiKey/OAuth2 派生）与 Jwt（JWT 过滤器）。
 * 无认证上下文（定时任务、事件监听、预认证过滤器）时返回全空主体——
 * actorUserId 为空即系统行为，保证 {@code @Audited} 标注的非 Web 方法也可记录。
 */
@Component
public class DefaultSecurityUserProvider implements SecurityUserProvider {
   final SecurityUserResolver securityUserResolver=new SecurityUserResolver();

    @Override
    public SecurityUsers currentUser() {
        return securityUserResolver.resolve(SecurityContextHolder.getContext().getAuthentication());
    }
}
