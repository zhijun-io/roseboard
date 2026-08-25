package com.roseboard.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.customer.CustomerService;
import com.roseboard.device.profile.DeviceProfileService;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import com.roseboard.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantProfileMapper tenantProfileMapper;
    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final DeviceProfileService deviceProfileService;
    private final CustomerService customerService;
    private final TenantDataCleanupMapper dataCleanupMapper;

    public TenantService(TenantMapper tenantMapper, TenantProfileMapper tenantProfileMapper,
                         CustomerMapper customerMapper, UserMapper userMapper, UserService userService,
                         DeviceProfileService deviceProfileService, CustomerService customerService,
                         TenantDataCleanupMapper dataCleanupMapper) {
        this.tenantMapper = tenantMapper;
        this.tenantProfileMapper = tenantProfileMapper;
        this.customerMapper = customerMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.deviceProfileService = deviceProfileService;
        this.customerService = customerService;
        this.dataCleanupMapper = dataCleanupMapper;
    }

    public TenantEntity findById(UUID tenantId) {
        return tenantMapper.selectById(tenantId);
    }

    public TenantEntity requireById(UUID tenantId) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        return tenant;
    }

    public List<TenantEntity> findByIds(Collection<UUID> tenantIds) {
        return tenantMapper.selectBatchIds(tenantIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantEntity save(TenantEntity tenant) {
        long now = System.currentTimeMillis();
        if (tenant.getId() == null) {
            tenant.setId(UUID.randomUUID());
            tenant.setCreatedTime(now);
            tenant.setVersion(1L);
            tenantMapper.insert(tenant);
            deviceProfileService.ensureDefaultForTenant(tenant.getId());
        } else {
            tenantMapper.updateById(tenant);
        }
        return tenantMapper.selectById(tenant.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteById(UUID tenantId) {
        TenantEntity tenant = requireById(tenantId);
        for (CustomerEntity customer : customerMapper.selectList(new LambdaQueryWrapper<CustomerEntity>()
                .eq(CustomerEntity::getTenantId, tenantId))) {
            customerService.deleteById(customer.getId());
        }
        for (UserEntity user : userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId))) {
            userService.deleteById(user.getId());
        }

        dataCleanupMapper.deleteNotificationsByTenant(tenantId);
        dataCleanupMapper.deleteNotificationTemplatesByTenant(tenantId);
        dataCleanupMapper.deleteNotificationChannelsByTenant(tenantId);
        dataCleanupMapper.deleteNotificationTargetsByTenant(tenantId);
        dataCleanupMapper.deleteUsageCountersByTenant(tenantId);
        dataCleanupMapper.deleteAdminSettingsByTenant(tenantId);
        dataCleanupMapper.deleteOAuth2ClientsByTenant(tenantId);
        dataCleanupMapper.deleteDomainsByTenant(tenantId);
        dataCleanupMapper.deleteQueueStatsByTenant(tenantId);
        dataCleanupMapper.deleteQueuesByTenant(tenantId);

        dataCleanupMapper.deleteDeviceRpcsByTenant(tenantId);
        dataCleanupMapper.deleteTelemetryPointsByTenant(tenantId);
        dataCleanupMapper.deleteTelemetryLatestByTenant(tenantId);
        dataCleanupMapper.deleteAttributesByTenant(tenantId);
        dataCleanupMapper.deleteDeviceCredentialsByTenant(tenantId);
        dataCleanupMapper.deleteDevicesByTenant(tenantId);
        dataCleanupMapper.clearProfileOtaReferences(tenantId);
        dataCleanupMapper.deleteOtaChunksByTenant(tenantId);
        dataCleanupMapper.deleteOtaPackagesByTenant(tenantId);
        dataCleanupMapper.deleteDeviceProfilesByTenant(tenantId);

        tenantMapper.deleteById(tenantId);
        dataCleanupMapper.deleteOrphanTenantProfile(tenant.getTenantProfileId());
    }

    public PageData<TenantEntity> findPage(long pageSize, long page, String textSearch) {
        Page<TenantEntity> result = tenantMapper.selectPage(pageSize, page, textSearch);
        enrichProfileNames(result.getRecords());
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    private void enrichProfileNames(List<TenantEntity> tenants) {
        tenants.forEach(tenant -> {
            if (tenant.getTenantProfileId() != null) {
                TenantProfileEntity profile = tenantProfileMapper.selectById(tenant.getTenantProfileId());
                if (profile != null) {
                    tenant.setTenantProfileName(profile.getName());
                }
            }
        });
    }

    public PageData<TenantEntity> findPageById(UUID tenantId, long pageSize, long page, String textSearch) {
        Page<TenantEntity> result = tenantMapper.selectPageById(tenantId, pageSize, page, textSearch);
        enrichProfileNames(result.getRecords());
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }
}
