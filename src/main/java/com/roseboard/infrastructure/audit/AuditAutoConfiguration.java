package com.roseboard.infrastructure.audit;

import com.roseboard.common.security.SecurityUserProvider;
import com.roseboard.infrastructure.audit.aspect.AuditAspect;
import com.roseboard.infrastructure.audit.aspect.AuditExpressionEvaluator;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.context.ChainedAuditContextProvider;
import com.roseboard.infrastructure.audit.event.AuditEventListener;
import com.roseboard.infrastructure.audit.writer.AuditWriter;
import com.roseboard.infrastructure.audit.writer.MetricsAuditWriter;
import com.roseboard.infrastructure.audit.writer.jdbc.AuditRetentionCleaner;
import com.roseboard.infrastructure.audit.writer.jdbc.JdbcAuditWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 审计运行时装配，受总开关 {@code roseboard.audit.enabled}（默认 true）控制：
 * false 时本配置类整体不加载——不创建写入器、指标、保留清理
 * 与 {@link AuditTemplate}；{@link AuditEventListener} 同步条件装配；
 * 使用方发布的事件无人接收，自然丢弃，业务零影响。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty(prefix = "roseboard.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditWriter.class)
    AuditWriter auditWriter(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                            AuditProperties properties) {
        AuditWriter writer = new JdbcAuditWriter(jdbcTemplate);
        if (properties.isMetricsEnabled()) {
            writer = new MetricsAuditWriter(writer, meterRegistry);
        }
        return writer;
    }

    @Bean
    @ConditionalOnMissingBean(AuditTemplate.class)
    AuditTemplate auditTemplate(AuditWriter writer) {
        return new DefaultAuditTemplate(writer);
    }

    @Bean
    @ConditionalOnNotWebApplication
    @ConditionalOnMissingBean(AuditContextProvider.class)
    AuditContextProvider auditContextProvider() {
        return new ChainedAuditContextProvider(AuditContext::empty);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnMissingBean(AuditContextProvider.class)
    AuditContextProvider reactiveAuditContextProvider() {
        return new ChainedAuditContextProvider(AuditContext::empty);
    }

    @Bean
    @ConditionalOnMissingBean(AuditEventListener.class)
    AuditEventListener auditEventListener(AuditTemplate template,
                                          AuditContextProvider contextProvider) {
        return new AuditEventListener(template, contextProvider);
    }

    @Bean
    @ConditionalOnMissingBean(AuditExpressionEvaluator.class)
    AuditExpressionEvaluator auditExpressionEvaluator() {
        return new AuditExpressionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean(AuditAspect.class)
    AuditAspect auditAspect(AuditTemplate template,
                            SecurityUserProvider securityUserProvider,
                            AuditExpressionEvaluator expressionEvaluator,
                            AuditContextProvider contextProvider) {
        return new AuditAspect(template, securityUserProvider, expressionEvaluator,
                contextProvider);
    }

    @Bean
    @ConditionalOnMissingBean(AuditRetentionCleaner.class)
    AuditRetentionCleaner auditRetentionCleaner(
            JdbcTemplate jdbcTemplate,
            AuditProperties properties,
            MeterRegistry meterRegistry) {
        return new AuditRetentionCleaner(jdbcTemplate, properties.getRetentionDays(), meterRegistry);
    }
}
