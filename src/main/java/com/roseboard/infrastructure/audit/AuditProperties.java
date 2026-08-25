package com.roseboard.infrastructure.audit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 审计运行时配置。
 *
 * <p>属性前缀：{@code roseboard.audit}。</p>
 */
@Validated
@ConfigurationProperties(prefix = "roseboard.audit")
public class AuditProperties {

    private boolean metricsEnabled = true;
    @Min(0)
    private long retentionDays;
    private String retentionCron = "0 0 3 * * *";

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public long getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(long retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }

}
