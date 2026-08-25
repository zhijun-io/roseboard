package com.roseboard.infrastructure.audit.servlet;

import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.context.ChainedAuditContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet 专用审计适配器。非 Servlet 应用不会加载本配置。
 */
@Configuration
@ConditionalOnProperty(prefix = "roseboard.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditServletConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditContextProvider.class)
    public AuditContextProvider auditContextProvider() {
        return new ChainedAuditContextProvider(new ServletAuditContextProvider());
    }
}
