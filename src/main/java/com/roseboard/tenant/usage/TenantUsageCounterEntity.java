package com.roseboard.tenant.usage;

import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

@TableName("tenant_usage_counter")
public class TenantUsageCounterEntity {
    private UUID tenantId;
    private String period;
    private String metricKey;
    private Long usedValue;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getUsedValue() { return usedValue; }
    public void setUsedValue(Long usedValue) { this.usedValue = usedValue; }
}
