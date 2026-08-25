package com.roseboard.customer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.tenant.usage.TenantUsageService;
import com.roseboard.tenant.TenantDataCleanupMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import com.roseboard.user.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final TenantUsageService usageService;
    private final TenantDataCleanupMapper dataCleanupMapper;

    public CustomerService(CustomerMapper customerMapper, UserMapper userMapper, UserService userService,
                           TenantUsageService usageService, TenantDataCleanupMapper dataCleanupMapper) {
        this.customerMapper = customerMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.usageService = usageService;
        this.dataCleanupMapper = dataCleanupMapper;
    }

    public CustomerEntity findById(UUID customerId) {
        return customerMapper.selectById(customerId);
    }

    public CustomerEntity requireById(UUID customerId) {
        CustomerEntity customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return customer;
    }

    public boolean belongsToTenant(UUID customerId, UUID tenantId) {
        return customerMapper.selectCount(new LambdaQueryWrapper<CustomerEntity>()
                .eq(CustomerEntity::getId, customerId)
                .eq(CustomerEntity::getTenantId, tenantId)) > 0;
    }

    public List<CustomerEntity> findByIds(Collection<UUID> customerIds) {
        return customerMapper.selectBatchIds(customerIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public CustomerEntity save(CustomerEntity customer) {
        long now = System.currentTimeMillis();
        if (customer.getId() == null) {
            usageService.requireEntityQuota(customer.getTenantId(), TenantUsageService.MAX_CUSTOMERS);
            customer.setId(UUID.randomUUID());
            customer.setCreatedTime(now);
            customer.setVersion(1L);
            customerMapper.insert(customer);
        } else {
            CustomerEntity existing = requireById(customer.getId());
            customer.setTenantId(existing.getTenantId());
            customer.setCreatedTime(existing.getCreatedTime());
            customer.setVersion(existing.getVersion());
            customerMapper.updateById(customer);
        }
        return customerMapper.selectById(customer.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteById(UUID customerId) {
        dataCleanupMapper.unassignDevicesFromCustomer(customerId);
        for (UserEntity user : userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getCustomerId, customerId))) {
            userService.deleteById(user.getId());
        }
        customerMapper.deleteById(customerId);
    }

    public PageData<CustomerEntity> findPage(long pageSize, long page, String textSearch) {
        return toPageData(customerMapper.selectPage(pageSize, page, textSearch), pageSize, page);
    }

    public PageData<CustomerEntity> findPageByTenant(UUID tenantId, long pageSize, long page, String textSearch) {
        return toPageData(customerMapper.selectPageByTenant(tenantId, pageSize, page, textSearch), pageSize, page);
    }

    private PageData<CustomerEntity> toPageData(Page<CustomerEntity> result, long pageSize, long page) {
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    public CustomerEntity requireBelongsToTenant(UUID customerId, UUID tenantId) {
        CustomerEntity customer = requireById(customerId);
        if (!tenantId.equals(customer.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer does not belong to device tenant");
        }
        return customer;
    }

    public CustomerEntity findByTenantAndTitle(UUID tenantId, String title) {
        return customerMapper.selectOne(new LambdaQueryWrapper<CustomerEntity>()
                .eq(CustomerEntity::getTenantId, tenantId)
                .eq(StringUtils.isNoneBlank(title), CustomerEntity::getTitle, title));
    }

    public CustomerEntity requireByTenantAndTitle(UUID tenantId, String title) {
        CustomerEntity customer = findByTenantAndTitle(tenantId, title);
        if (customer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return customer;
    }
}
