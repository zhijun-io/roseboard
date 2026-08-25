package com.roseboard.tenant.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TenantCoreIsolationService {
    private final TenantMapper tenantMapper;
    private final TenantProfileMapper tenantProfileMapper;

    public TenantCoreIsolationService(TenantMapper tenantMapper, TenantProfileMapper tenantProfileMapper) {
        this.tenantMapper = tenantMapper;
        this.tenantProfileMapper = tenantProfileMapper;
    }

    public boolean isIsolated(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getTenantProfileId() == null) {
            return false;
        }
        TenantProfileEntity profile = tenantProfileMapper.selectById(tenant.getTenantProfileId());
        return profile != null && Boolean.TRUE.equals(profile.getIsolatedTbCore());
    }

    public List<UUID> isolatedTenantIds() {
        List<TenantProfileEntity> profiles = tenantProfileMapper.selectList(
                new LambdaQueryWrapper<TenantProfileEntity>()
                        .eq(TenantProfileEntity::getIsolatedTbCore, true));
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<UUID> profileIds = profiles.stream().map(TenantProfileEntity::getId).toList();
        return tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                        .in(TenantEntity::getTenantProfileId, profileIds))
                .stream()
                .map(TenantEntity::getId)
                .toList();
    }
}
