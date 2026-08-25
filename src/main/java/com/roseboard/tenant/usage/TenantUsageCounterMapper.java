package com.roseboard.tenant.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface TenantUsageCounterMapper extends BaseMapper<TenantUsageCounterEntity> {
    default Long selectUsed(UUID tenantId, String period, String metricKey) {
        TenantUsageCounterEntity entity = selectOne(wrapper(tenantId, period, metricKey));
        return entity == null ? null : entity.getUsedValue();
    }

    @Select("""
            insert into tenant_usage_counter (tenant_id, period, metric_key, used_value)
            values (#{tenantId}, #{period}, #{metricKey}, #{delta})
            on conflict (tenant_id, period, metric_key)
            do update set used_value = tenant_usage_counter.used_value + excluded.used_value
            returning used_value
            """)
    long incrementAndSelect(UUID tenantId, String period, String metricKey, long delta);

    private static LambdaQueryWrapper<TenantUsageCounterEntity> wrapper(UUID tenantId, String period,
                                                                        String metricKey) {
        return new LambdaQueryWrapper<TenantUsageCounterEntity>()
                .eq(TenantUsageCounterEntity::getTenantId, tenantId)
                .eq(TenantUsageCounterEntity::getPeriod, period)
                .eq(TenantUsageCounterEntity::getMetricKey, metricKey);
    }
}
